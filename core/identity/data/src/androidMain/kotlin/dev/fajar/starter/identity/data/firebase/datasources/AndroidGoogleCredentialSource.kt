package dev.fajar.starter.identity.data.firebase.datasources

import android.content.Context
import android.os.CancellationSignal
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.credentials.*
import androidx.credentials.exceptions.*
import androidx.lifecycle.Lifecycle
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dev.fajar.starter.identity.data.firebase.boundary.*
import dev.fajar.starter.identity.data.firebase.dto.GoogleCredentialResult
import dev.fajar.starter.identity.data.firebase.mappers.requireGoogleCredentialNonce
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.util.Base64

/** Activity owns attachment; each Credential Manager request owns its cancellation signal. */
class AndroidGoogleCredentialSource(context: Context) : GoogleCredentialSource {
    private val application = context.applicationContext
    private val manager = CredentialManager.create(application)
    private var owner = WeakReference<ComponentActivity>(null)
    private var active: GoogleCredentialSession? = null
    private var closed = false
    override val supported = true

    fun attach(activity: ComponentActivity) {
        check(!closed)
        active?.close()
        owner = WeakReference(activity)
    }

    fun detach(activity: ComponentActivity) {
        if (owner.get() !== activity) return
        try {
            active?.close()
        } finally {
            owner.clear()
        }
    }

    override suspend fun open(clientId: String): GoogleCredentialSession {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed && active == null)
        val activity = owner.get() ?: throw GoogleCredentialUnavailable()
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            throw GoogleCredentialUnavailable()
        val nonce =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val request =
            GetCredentialRequest.Builder()
                .addCredentialOption(
                    GetSignInWithGoogleOption.Builder(clientId).setNonce(nonce).build()
                )
                .build()
        val host = WeakReference(activity)
        return CallbackGoogleCredentialSession(
                { callback ->
                    val current = host.get() ?: throw GoogleCredentialUnavailable()
                    check(
                        owner.get() === current &&
                            current.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    )
                    val signal = CancellationSignal()
                    try {
                        manager.getCredentialAsync(
                            current,
                            request,
                            signal,
                            ContextCompat.getMainExecutor(application),
                            object :
                                CredentialManagerCallback<
                                    GetCredentialResponse,
                                    GetCredentialException,
                                > {
                                override fun onResult(result: GetCredentialResponse) {
                                    try {
                                        val credential = result.credential
                                        require(
                                            credential is CustomCredential &&
                                                credential.type ==
                                                    GoogleIdTokenCredential
                                                        .TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                        )
                                        val token =
                                            GoogleIdTokenCredential.createFrom(credential.data)
                                                .idToken
                                        requireGoogleCredentialNonce(token, clientId, nonce)
                                        callback.success(GoogleCredentialResult.IdToken(token))
                                    } catch (error: Exception) {
                                        callback.failure(GoogleCredentialUnavailable(error))
                                    }
                                }

                                override fun onError(error: GetCredentialException) {
                                    callback.failure(
                                        if (error is GetCredentialCancellationException)
                                            GoogleCredentialCancelled(error)
                                        else GoogleCredentialUnavailable(error)
                                    )
                                }
                            },
                        )
                        AutoCloseable { signal.cancel() }
                    } catch (error: Exception) {
                        signal.cancel()
                        throw error
                    }
                },
                { active = null },
            )
            .also { active = it }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            active?.close()
        } finally {
            owner.clear()
        }
    }
}
