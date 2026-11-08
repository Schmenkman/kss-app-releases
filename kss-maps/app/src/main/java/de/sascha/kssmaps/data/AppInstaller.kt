package de.sascha.kssmaps.data

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Installation und Updates so, dass KSS-Maps in Android Auto sichtbar bleibt.
 *
 * Android Auto zeigt selbst installierte Navigations-Apps nur, wenn Android sie als "aus dem Play
 * Store installiert" fuehrt. Das erreicht KingInstaller. Wird ein Update dagegen mit dem normalen
 * Android-Installer installiert (z. B. "Oeffnen" nach dem Download im Browser), merkt sich Android
 * diesen als Quelle – Android Auto blendet KSS-Maps aus und oeffnet stattdessen Google Maps.
 *
 * Deshalb: Quelle pruefen (Warnung + Reparatur) und Updates selbst laden und direkt an KingInstaller
 * uebergeben (dort nur noch "Installieren" tippen).
 */
object AppInstaller {
    const val PLAY_STORE = "com.android.vending"
    /** KingInstaller (github.com/fcaronte/KingInstaller) – nimmt APKs per "Oeffnen mit" entgegen. */
    const val KING_INSTALLER = "com.example.kinginstaller"
    const val KING_INSTALLER_URL = "https://github.com/fcaronte/KingInstaller/releases"
    const val ANDROID_AUTO = "com.google.android.projection.gearhead"
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** Wer hat die App (zuletzt) installiert? null = unbekannt (z. B. per Kabel). */
    fun installer(ctx: Context): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getInstallerPackageName(ctx.packageName)
        }
    } catch (e: Exception) {
        null
    }

    /** Sieht Android Auto die App (Quelle "Play Store", z. B. dank KingInstaller)? */
    fun visibleInAndroidAuto(ctx: Context): Boolean = installer(ctx) == PLAY_STORE

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun androidAutoInstalled(ctx: Context) = isInstalled(ctx, ANDROID_AUTO)
    fun kingInstallerInstalled(ctx: Context) = isInstalled(ctx, KING_INSTALLER)

    /** Download-Adresse der APK einer veroeffentlichten Version. */
    fun apkUrl(version: String) = "https://github.com/Schmenkman/kss-app-releases/releases/download/kssmaps-v$version/kss-maps.apk"

    enum class Outcome {
        /** KingInstaller hat die Datei – dort "Installieren" tippen. */
        OPENED_IN_KING_INSTALLER,
        /** Aeltere KingInstaller-Version: gestartet, die Datei dort im Ordner "Download" waehlen. */
        KING_INSTALLER_STARTED,
        /** KingInstaller fehlt. */
        NO_KING_INSTALLER,
        FAILED,
    }

    /** Laufender Download (zum Abbrechen). */
    class Download(val id: Long, val job: Job, val fileName: String)

    /**
     * Laedt die APK in den Ordner "Download" (Android-Download, Benachrichtigung nur waehrend des
     * Ladens – kein "Oeffnen" danach, das wuerde den normalen Installer nehmen) und gibt sie an
     * KingInstaller weiter. progress: Prozent (oder -1, solange unbekannt).
     */
    fun download(
        activity: Activity,
        scope: CoroutineScope,
        url: String,
        version: String,
        progress: (Int) -> Unit,
        done: (Outcome, String) -> Unit,
    ): Download? {
        val dm = activity.getSystemService(DownloadManager::class.java) ?: return null
        val fileName = "kss-maps-$version.apk"
        val id = try {
            dm.enqueue(
                DownloadManager.Request(Uri.parse(url))
                    .setTitle("KSS-Maps $version")
                    .setDescription("App-Update")
                    .setMimeType(APK_MIME)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName),
            )
        } catch (e: Exception) {
            return null
        }
        val job = scope.launch {
            while (true) {
                var status = DownloadManager.STATUS_PENDING
                var pct = -1
                var name = fileName
                try {
                    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (c.moveToFirst()) {
                            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            val now = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            if (total > 0) pct = (now * 100 / total).toInt()
                            c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))?.let { u ->
                                Uri.parse(u).lastPathSegment?.let { name = it }
                            }
                        } else {
                            status = DownloadManager.STATUS_FAILED // abgebrochen
                        }
                    }
                } catch (e: Exception) {
                    status = DownloadManager.STATUS_FAILED
                }
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = try {
                            dm.getUriForDownloadedFile(id)
                        } catch (e: Exception) {
                            null
                        }
                        done(if (uri == null) Outcome.FAILED else handOver(activity, uri), name)
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        done(Outcome.FAILED, name)
                        return@launch
                    }
                    else -> progress(pct)
                }
                delay(500)
            }
        }
        return Download(id, job, fileName)
    }

    fun cancel(activity: Activity, d: Download) {
        d.job.cancel()
        try {
            activity.getSystemService(DownloadManager::class.java)?.remove(d.id)
        } catch (e: Exception) {
            // schon weg
        }
    }

    /** APK an KingInstaller geben (ist sie dort vorgewaehlt, fehlt nur noch "Installieren"). */
    fun handOver(activity: Activity, apk: Uri): Outcome {
        if (!kingInstallerInstalled(activity)) return Outcome.NO_KING_INSTALLER
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apk, APK_MIME)
            .setPackage(KING_INSTALLER)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(view)
            return Outcome.OPENED_IN_KING_INSTALLER
        } catch (e: ActivityNotFoundException) {
            // aeltere Version ohne "Oeffnen mit"
        } catch (e: SecurityException) {
            // Freigabe der Datei abgelehnt
        }
        val launch = activity.packageManager.getLaunchIntentForPackage(KING_INSTALLER) ?: return Outcome.NO_KING_INSTALLER
        return try {
            activity.startActivity(launch)
            Outcome.KING_INSTALLER_STARTED
        } catch (e: Exception) {
            Outcome.NO_KING_INSTALLER
        }
    }
}
