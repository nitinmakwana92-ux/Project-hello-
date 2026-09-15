package com.example.mycompose.hello.utils

import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CoinDCXSigner {
    
    fun generateSignature(secretKey: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(secretKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(secretKeySpec)
        val hash = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun createPayload(params: Map<String, Any>): String {
        // CoinDCX payload JSON format mein Base64 encoded hona chahiye
        val jsonString = params.entries.joinToString(",", prefix = "{", postfix = "}") { 
            "\"${it.key}\":\"${it.value}\"" 
        }
        return Base64.encodeToString(jsonString.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }
}