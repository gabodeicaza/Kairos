package kairos.proyecto.data.privacy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cifrado de texto; permite sustituir la implementación en pruebas. */
interface TextCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String
}

/** AES-256-GCM con clave no exportable en Android Keystore. Salida: Base64(IV || texto cifrado). */
class KeystoreTextCipher(private val alias: String = "kairos_sensitive_key") : TextCipher {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * Almacén de datos sensibles: dirección del hogar, código postal, colonia y perfil de vulnerabilidad.
 * Nada se escribe en texto plano; también se puede borrar todo (derecho de cancelación / ARCO).
 */
class SecureStore(context: Context, private val cipher: TextCipher = KeystoreTextCipher()) {

    private val prefs = context.getSharedPreferences("secure_store", Context.MODE_PRIVATE)

    fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, cipher.encrypt(value)) }.apply()
    }

    /** Devuelve null si no existe o si el dato no se puede descifrar (p. ej. clave invalidada). */
    fun get(key: String): String? =
        prefs.getString(key, null)?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    fun wipe() = prefs.edit().clear().apply()

    companion object {
        const val HOME_ADDRESS = "home_address"
        const val POSTAL_CODE = "postal_code"
        const val COLONIA = "colonia"
        const val HOUSEHOLD_PROFILE = "household_profile"
    }
}
