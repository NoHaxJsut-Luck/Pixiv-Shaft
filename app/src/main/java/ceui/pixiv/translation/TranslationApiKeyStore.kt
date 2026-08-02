package ceui.pixiv.translation

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.tencent.mmkv.MMKV
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the xAI API key encrypted by an Android Keystore-backed AES key. */
object TranslationApiKeyStore {

    private const val TAG = "TranslationApiKeyStore"
    private const val STORE_ID = "shaft-session"
    private const val LEGACY_KEY = "translation_xai_api_key"
    private const val ENCRYPTED_KEY = "translation_xai_api_key_encrypted"
    private const val IV_KEY = "translation_xai_api_key_iv"
    private const val KEY_ALIAS = "shaft_translation_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun store(): MMKV = MMKV.mmkvWithID(STORE_ID)

    @JvmStatic
    @Synchronized
    fun getApiKey(): String {
        val encrypted = store().decodeString(ENCRYPTED_KEY, "").orEmpty()
        val iv = store().decodeString(IV_KEY, "").orEmpty()
        if (encrypted.isNotEmpty() && iv.isNotEmpty()) {
            return decrypt(encrypted, iv).orEmpty().trim()
        }

        // Transparently migrate keys saved by older builds as plain MMKV strings.
        val legacyValue = store().decodeString(LEGACY_KEY, "").orEmpty().trim()
        if (legacyValue.isNotEmpty() && setApiKey(legacyValue)) {
            store().removeValueForKey(LEGACY_KEY)
        }
        return legacyValue
    }

    @JvmStatic
    @Synchronized
    fun setApiKey(key: String): Boolean {
        val normalized = key.trim()
        if (normalized.isEmpty()) {
            clear()
            return true
        }

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
            store().encode(ENCRYPTED_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            store().encode(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            store().removeValueForKey(LEGACY_KEY)
            true
        }.getOrElse {
            Log.e(TAG, "Unable to securely save translation API key", it)
            false
        }
    }

    @JvmStatic
    fun getSettingsSummary(notSetLabel: String): String {
        val key = getApiKey()
        if (key.isEmpty()) return notSetLabel
        return if (key.length <= 8) "····" else "····${key.takeLast(4)}"
    }

    private fun decrypt(encrypted: String, iv: String): String? {
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val plain = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
            String(plain, Charsets.UTF_8)
        }.getOrElse {
            Log.e(TAG, "Unable to decrypt translation API key; clearing invalid value", it)
            clear()
            null
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun clear() {
        store().removeValueForKey(LEGACY_KEY)
        store().removeValueForKey(ENCRYPTED_KEY)
        store().removeValueForKey(IV_KEY)
    }
}
