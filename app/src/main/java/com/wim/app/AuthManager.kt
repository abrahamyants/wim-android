package com.wim.app

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await
import java.io.IOException

sealed class SignInResult {
    data class Success(val user: FirebaseUser) : SignInResult()
    data class Failed(val reason: String) : SignInResult()
}

/**
 * Manages Firebase Auth state and token retrieval.
 * Supports Google Sign-In and anonymous auth as fallback.
 */
object AuthManager {

    private const val TAG = "WiM"
    private val auth = FirebaseAuth.getInstance()

    val currentUser: FirebaseUser? get() = auth.currentUser
    val isSignedIn: Boolean get() = auth.currentUser != null

    /**
     * Get a fresh Firebase ID token for API calls.
     * Returns null if not signed in.
     */
    suspend fun getIdToken(): String? {
        val user = auth.currentUser ?: return null
        return try {
            user.getIdToken(false).await().token
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get ID token: ${e.message}")
            // Try force refresh
            try {
                user.getIdToken(true).await().token
            } catch (e2: Exception) {
                Log.e(TAG, "Token refresh also failed: ${e2.message}")
                null
            }
        }
    }

    /**
     * Sign in with Google credential (ID token from Credential Manager).
     */
    suspend fun signInWithGoogle(googleIdToken: String): SignInResult {
        return try {
            val credential = GoogleAuthProvider.getCredential(googleIdToken, null)
            val result = auth.signInWithCredential(credential).await()
            Log.i(TAG, "Signed in as: ${result.user?.email}")
            val user = result.user ?: return SignInResult.Failed("Try again")
            SignInResult.Success(user)
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            Log.e(TAG, "Google sign-in failed: ${e.message}")
            SignInResult.Failed("Wrong account")
        } catch (e: FirebaseAuthUserCollisionException) {
            Log.e(TAG, "Google sign-in failed: ${e.message}")
            SignInResult.Failed("Account conflict")
        } catch (e: IOException) {
            Log.e(TAG, "Google sign-in failed: ${e.message}")
            SignInResult.Failed("No internet")
        } catch (e: Exception) {
            Log.e(TAG, "Google sign-in failed: ${e.message}")
            SignInResult.Failed("Try again")
        }
    }

    fun signOut() {
        auth.signOut()
    }
}
