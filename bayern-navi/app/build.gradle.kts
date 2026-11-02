import groovy.json.JsonSlurper
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

android {
    namespace = "de.sascha.bayernnavi"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.sascha.bayernnavi"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
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
            //  2. lokal: signing/navi.p12 + signing/secrets.env (tools/make_keystore.py)
            // Ein Schluesselwechsel erzwingt auf dem Handy eine Neuinstallation.
            fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
            val local: Map<String, String> = rootProject.file("signing/secrets.env").takeIf { it.exists() }
                ?.readLines()?.mapNotNull { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                }?.toMap() ?: emptyMap()
            val ks = env("NAVI_KEYSTORE")?.let { file(it) } ?: rootProject.file("signing/navi.p12").takeIf { it.exists() }
            if (ks != null) {
                storeFile = ks
                storePassword = env("NAVI_STORE_PASSWORD") ?: local["NAVI_STORE_PASSWORD"]
                keyAlias = env("NAVI_KEY_ALIAS") ?: local["NAVI_KEY_ALIAS"] ?: "navi"
                keyPassword = env("NAVI_KEY_PASSWORD") ?: local["NAVI_KEY_PASSWORD"] ?: storePassword
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
}
