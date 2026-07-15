package com.wim.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CloudEntitlement(val unlocked: Boolean, val tier: String)

/** Authenticated entitlement reads and Google Play purchase verification. */
object BillingBackendClient {
    const val PRODUCT_ID = "wim_cloud_unlock"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun status(): CloudEntitlement? = post(
        JSONObject().put("action", "billing_status")
    )

    suspend fun verify(purchaseToken: String): CloudEntitlement? = post(
        JSONObject()
            .put("action", "verify_purchase")
            .put("product_id", PRODUCT_ID)
            .put("purchase_token", purchaseToken)
    )

    private suspend fun post(payload: JSONObject): CloudEntitlement? = withContext(Dispatchers.IO) {
        val token = AuthManager.getIdToken() ?: return@withContext null
        val request = Request.Builder()
            .url(BackendConfig.URL)
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    Log.w("WiM-Billing", "Backend ${response.code}: ${message.orEmpty()}")
                    return@withContext null
                }
                val json = JSONObject(text)
                CloudEntitlement(
                    unlocked = json.optBoolean("unlocked", false),
                    tier = json.optString("tier", "invite"),
                )
            }
        } catch (e: Exception) {
            Log.e("WiM-Billing", "Entitlement request failed: ${e.message}", e)
            null
        }
    }
}
