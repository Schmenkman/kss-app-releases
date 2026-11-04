package de.sascha.kssmaps.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Zugangsdaten verschluesselt auf dem Geraet: AES-256-GCM mit einem Schluessel im Android
 * Keystore (verlaesst das Geraet nie; eine Datensicherung auf ein anderes Handy ist damit
 * unlesbar – dort einfach neu eintragen). Nie im Code, nie im Repo, nie im Log.
 */
class SecretStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("kss_secret", Context.MODE_PRIVATE)

    fun has(name: String): Boolean = sp.contains(name)

    fun put(name: String, value: String) {
        if (value.isEmpty()) {
            sp.edit().remove(name).apply()
            return
        }
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val data = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        sp.edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    /** Entschluesselt; "" wenn nicht vorhanden oder nicht lesbar (z. B. nach Geraetewechsel). */
    fun get(name: String): String {
        val s = sp.getString(name, null) ?: return ""
        return try {
            val raw = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
            String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun remove(vararg names: String) {
        val e = sp.edit()
        for (n in names) e.remove(n)
        e.apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return kg.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "kss_maps_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
    }
}
