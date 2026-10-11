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

/** What the server says this account may use. maxLayer is the highest gear. */
data class CloudEntitlement(val unlocked: Boolean, val tier: String, val maxLayer: Int)

/** Authenticated entitlement reads and Google Play purchase verification. */
object BillingBackendClient {
    /** WiM Cloud: gears 1-3 (Transcribe, Reconstruct, Profile). */
    const val PRODUCT_ID = "wim_cloud_monthly"
    /** WiM Cloud Plus: gears 1-4, adds Speech Disfluency. Both plans since 2026-10-11. */
    const val PLUS_PRODUCT_ID = "wim_cloud_plus_monthly"
    const val BASE_PLAN_ID = "monthly"
    val PRODUCT_IDS = listOf(PRODUCT_ID, PLUS_PRODUCT_ID)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun status(): CloudEntitlement? = post(
        JSONObject().put("action", "billing_status")
    )

    suspend fun verify(purchaseToken: String, productId: String): CloudEntitlement? = post(
        JSONObject()
            .put("action", "verify_purchase")
            .put("product_id", productId)
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
                val unlocked = json.optBoolean("unlocked", false)
                CloudEntitlement(
                    unlocked = unlocked,
                    tier = json.optString("tier", "invite"),
                    // Older server replies carry no max_layer; a paid account
                    // then gets the lower plan's gears until the next status read.
                    maxLayer = json.optInt("max_layer", if (unlocked) 3 else 1),
                )
            }
        } catch (e: Exception) {
            Log.e("WiM-Billing", "Entitlement request failed: ${e.message}", e)
            null
        }
    }
}
