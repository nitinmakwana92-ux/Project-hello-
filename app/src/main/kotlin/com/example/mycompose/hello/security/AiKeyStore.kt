package com.example.mycompose.hello.Ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Secure local storage for AI provider API keys. Keys are never shipped in source. */
object AiKeyStore {
    private const val KS = "AndroidKeyStore"
    private const val ALIAS = "hello_ai_api_keys_v1"
    private const val PREFS = "ai_secure_keys"
    private const val QWEN = "qwen"
    private const val OPENAI = "openai"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun put(context: Context, provider: String, value: String): Boolean {
        val clean = value.trim()
        if (clean.isBlank()) {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(provider).commit()
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(clean.toByteArray(StandardCharsets.UTF_8))
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(provider, packed).commit()
            saved && get(context, provider) == clean
        } catch (_: Exception) {
            false
        }
    }

    fun get(context: Context, provider: String): String {
        val packed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(provider, null)
            ?: return ""
        return try {
            val parts = packed.split('.', limit = 2)
            if (parts.size != 2) return ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun migrateLegacy(context: Context) {
        val prefs = context.getSharedPreferences("trading_bot_prefs", Context.MODE_PRIVATE)
        val qwen = prefs.getString("ai_qwen_api_key", "").orEmpty()
        val openai = prefs.getString("openai_api_key", "").orEmpty()
        var removeLegacyQwen = qwen.isBlank()
        var removeLegacyOpenAi = openai.isBlank()
        if (qwen.isNotBlank() && get(context, QWEN).isBlank()) {
            removeLegacyQwen = put(context, QWEN, qwen)
        } else if (get(context, QWEN).isNotBlank()) {
            removeLegacyQwen = true
        }
        if (openai.isNotBlank() && get(context, OPENAI).isBlank()) {
            removeLegacyOpenAi = put(context, OPENAI, openai)
        } else if (get(context, OPENAI).isNotBlank()) {
            removeLegacyOpenAi = true
        }
        val edit = prefs.edit()
        if (removeLegacyQwen) edit.remove("ai_qwen_api_key")
        if (removeLegacyOpenAi) edit.remove("openai_api_key")
        edit.commit()
    }

    const val PROVIDER_QWEN = QWEN
    const val PROVIDER_OPENAI = OPENAI

    private const val QWEN_BASE_URL = "qwen_base_url"

    fun putQwenBaseUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(QWEN_BASE_URL, value.trim().trimEnd('/')).apply()
    }

    fun getQwenBaseUrl(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(QWEN_BASE_URL, "").orEmpty()
    }
}
