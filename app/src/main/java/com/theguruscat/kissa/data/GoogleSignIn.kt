package com.theguruscat.kissa.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.theguruscat.kissa.BuildConfig

/**
 * "Continue with Google": shows Google's account picker and returns an ID token for the relay to check
 * (POST /auth/google). Needs [BuildConfig.GOOGLE_CLIENT_ID]; without it the button isn't shown.
 */
object GoogleSignIn {

    val isAvailable: Boolean get() = BuildConfig.GOOGLE_CLIENT_ID.isNotEmpty()

    /** The ID token, or null if the person closed the picker. [context] must be an Activity. */
    suspend fun idToken(context: Context): String? {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            CredentialManager.create(context).getCredential(context, request).credential
        } catch (e: GetCredentialCancellationException) {
            return null
        } catch (e: GetCredentialException) {
            throw AccountException("Google sign-in didn't work: ${e.message ?: "try again"}")
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw AccountException("Google sign-in didn't work. Try again.")
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
}
