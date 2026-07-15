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

/**
 * GDPR/CCPA user data rights endpoints.
 *
 * Both export and delete go through the same backend Cloud Function
 * at us-central1-bakers-agent.cloudfunctions.net/wim-reconstruct,
 * differentiated by the "action" field in the POST body.
 *
 * Auth: Firebase ID token in Authorization: Bearer header.
 */
object UserDataClient {

    private const val ENDPOINT = BackendConfig.URL

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Export user's Firestore data as raw JSON.
     * Returns the full wim_users/{uid} document (shared with Лаврентий desktop).
     *
     * @return JSONObject with "data" key containing the profile, or null on failure
     */
    suspend fun exportData(): JSONObject? = withContext(Dispatchers.IO) {
        val token = AuthManager.getIdToken() ?: return@withContext null
        val body = JSONObject().put("action", "export_data").toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: return@withContext null
                if (!resp.isSuccessful) {
                    Log.e("UserDataClient", "exportData HTTP ${resp.code}")
                    return@withContext null
                }
                JSONObject(respBody)
            }
        } catch (e: Exception) {
            Log.e("UserDataClient", "exportData failed: ${e.message}", e)
            null
        }
    }

    /**
     * Delete the Firebase account and all WiM cloud data. Hard delete, immediate.
     * Removes nested sessions, purchase bindings, and wim_users/{uid} before
     * deleting Firebase Authentication. This affects linked Lavrentiy data too.
     *
     * @return true if deletion succeeded
     */
    suspend fun deleteData(): Boolean = withContext(Dispatchers.IO) {
        val token = AuthManager.getIdToken() ?: return@withContext false
        val body = JSONObject().put("action", "delete_data").toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: return@withContext false
                if (!resp.isSuccessful) {
                    Log.e("UserDataClient", "deleteData HTTP ${resp.code}")
                    return@withContext false
                }
                JSONObject(respBody).optBoolean("deleted", false)
            }
        } catch (e: Exception) {
            Log.e("UserDataClient", "deleteData failed: ${e.message}", e)
            false
        }
    }
}
