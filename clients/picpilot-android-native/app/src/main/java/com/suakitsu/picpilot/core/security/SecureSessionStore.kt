package com.suakitsu.picpilot.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureSessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("picpilot_secure_session", Context.MODE_PRIVATE)
    private val _token = MutableStateFlow(readToken())
    val token: StateFlow<String> = _token.asStateFlow()

    fun currentToken(): String = _token.value

    fun saveToken(token: String, persistent: Boolean = true) {
        if (!persistent) {
            preferences.edit().clear().apply()
            _token.value = token
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val bytes = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        preferences.edit()
            .putString(KEY_TOKEN, Base64.encodeToString(bytes, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        _token.value = token
    }

    fun clear() {
        preferences.edit().clear().apply()
        _token.value = ""
    }

    private fun readToken(): String {
        val encoded = preferences.getString(KEY_TOKEN, null) ?: return ""
        val iv = preferences.getString(KEY_IV, null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), StandardCharsets.UTF_8)
        }.getOrElse {
            preferences.edit().clear().apply()
            ""
        }
    }

    private fun encryptionKey(): SecretKey {
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

    private companion object {
        const val KEY_ALIAS = "picpilot.session.aes.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_TOKEN = "token"
        const val KEY_IV = "iv"
    }
}
