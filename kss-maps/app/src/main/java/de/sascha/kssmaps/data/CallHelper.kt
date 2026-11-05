package de.sascha.kssmaps.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log

/**
 * Direkt waehlen – ohne Waehlfeld und ohne die Telefon-App nach vorne zu holen: der Anruf
 * laeuft ueber das Telefon des Handys (in Android Auto erscheint er nur als kleine Anrufkarte,
 * die Navigation bleibt die grosse Ansicht). Braucht die Berechtigung "Anrufe taetigen".
 */
object CallHelper {
    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    /** true = Anruf gestartet; false = keine Berechtigung oder Telefon nicht verfuegbar. */
    fun call(ctx: Context, digits: String): Boolean {
        if (digits.isBlank() || !hasPermission(ctx)) return false
        return try {
            val tm = ctx.getSystemService(TelecomManager::class.java) ?: return false
            tm.placeCall(Uri.fromParts("tel", digits, null), Bundle())
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            Log.w("KSSMaps", "Anruf: ${e.javaClass.simpleName}")
            false
        }
    }
}
