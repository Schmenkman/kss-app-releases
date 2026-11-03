import groovy.json.JsonSlurper
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// version.json ist die EINZIGE Versionsquelle (gleiches Muster wie bei den anderen Apps):
// Die App liest sie nicht selbst, aber versionName/versionCode kommen von hier und die CI
// benutzt dieselbe Datei fuer das Release. Schema versionCode: major*10000 + minor*100 + patch.
@Suppress("UNCHECKED_CAST")
val versionInfo = JsonSlurper().parse(rootProject.file("version.json")) as Map<String, Any>
val appVersion = versionInfo["version"] as String
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { it[0] * 10000 + it[1] * 100 + it[2] }

// API-Schluessel (TomTom, Tankerkoenig) kommen beim Bauen aus den GitHub-Secrets
// KSSMAPS_TOMTOM_KEY / KSSMAPS_TANKERKOENIG_KEY – sie stehen NIE im (oeffentlichen) Repo.
// In der APK leicht verschleiert (XOR + Base64, Gegenstueck: data/BuiltinKeys.kt), damit sie
// dort nicht als Klartext stehen. Ohne Secrets (lokaler Build, Testfahrt): leer.
fun builtinKey(env: String): String {
    val v = (System.getenv(env) ?: "").trim()
    if (v.isEmpty()) return ""
    val mask = "kss-maps".toByteArray()
    val b = v.toByteArray()
    return Base64.getEncoder().encodeToString(ByteArray(b.size) { (b[it].toInt() xor mask[it % mask.size].toInt()).toByte() })
}

android {
    namespace = "de.sascha.kssmaps"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.sascha.kssmaps"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "BUILTIN_TOMTOM", "\"" + builtinKey("KSSMAPS_TOMTOM_KEY") + "\"")
        buildConfigField("String", "BUILTIN_TANKERKOENIG", "\"" + builtinKey("KSSMAPS_TANKERKOENIG_KEY") + "\"")
        // CI ohne eigene Signier-Secrets: temporaerer Schluessel -> Updates nur nach Deinstallation
        buildConfigField("boolean", "TEMP_SIGNING_KEY", (System.getenv("KSSMAPS_TEMP_KEY") == "1").toString())
        ndk {
            // Nur echte Handys – halbiert die APK-Groesse (MapLibre bringt native Bibliotheken mit).
            // -PallAbis=true (Emulator-Test in der CI) baut zusaetzlich x86/x86_64.
            if (!project.hasProperty("allAbis")) abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            // Der Schluessel liegt NICHT im Repo. Quellen in dieser Reihenfolge:
            //  1. CI: Umgebungsvariablen (aus GitHub-Secrets bzw. temporaerer Test-Schluessel)
            //  2. lokal: signing/kssmaps.p12 + signing/secrets.env (tools/make_keystore.py)
            // Ein Schluesselwechsel erzwingt auf dem Handy eine Neuinstallation.
            fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
            val local: Map<String, String> = rootProject.file("signing/secrets.env").takeIf { it.exists() }
                ?.readLines()?.mapNotNull { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                }?.toMap() ?: emptyMap()
            val ks = env("KSSMAPS_KEYSTORE")?.let { file(it) } ?: rootProject.file("signing/kssmaps.p12").takeIf { it.exists() }
            if (ks != null) {
                storeFile = ks
                storePassword = env("KSSMAPS_STORE_PASSWORD") ?: local["KSSMAPS_STORE_PASSWORD"]
                keyAlias = env("KSSMAPS_KEY_ALIAS") ?: local["KSSMAPS_KEY_ALIAS"] ?: "kssmaps"
                keyPassword = env("KSSMAPS_KEY_PASSWORD") ?: local["KSSMAPS_KEY_PASSWORD"] ?: storePassword
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            // Ohne Schluessel (lokaler Test-Build) mit dem Debug-Schluessel signieren.
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    // Android-Auto-Tests (Robolectric) nur mit -PcarTests – so kann ein Fehler dort den
    // normalen Build nicht aufhalten.
    if (project.hasProperty("carTests")) {
        sourceSets.getByName("test").java.srcDir("src/carTest/java")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Android Auto (Car App Library) – Navigations-Kategorie mit eigener Kartenflaeche
    implementation("androidx.car.app:app:1.4.0")
    // Vektorkarte (OpenGL-Variante, laeuft auch auf der Android-Auto-Flaeche)
    implementation("org.maplibre.gl:android-sdk:11.13.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Android-Auto-Vorlagen ohne Geraet pruefen (Robolectric + Car-App-Testbibliothek)
    testImplementation("androidx.car.app:app-testing:1.4.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.6.1")
}

// Fehlgeschlagene Tests mit vollstaendigem Stacktrace im CI-Log zeigen.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
