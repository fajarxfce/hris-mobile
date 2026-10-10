package dev.fajar.starter.security.data.lock.datasources

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The host owns attachment. Only an active prompt may retain the current Activity. */
class AndroidDeviceAuthenticationSource(context: Context) : DeviceAuthenticationSource {
    private val application = context.applicationContext
    private var owner = WeakReference<FragmentActivity>(null)
    private var prompt: BiometricPrompt? = null
    private val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
    private val operation = Mutex()
    private var pending: CancellableContinuation<Boolean>? = null

    fun attach(activity: FragmentActivity) {
        pending?.cancel()
        prompt?.cancelAuthentication()
        prompt = null
        owner = WeakReference(activity)
    }

    fun detach(activity: FragmentActivity) {
        if (owner.get() !== activity) return
        pending?.cancel()
        prompt?.cancelAuthentication()
        prompt = null
        owner.clear()
    }

    override suspend fun available() =
        BiometricManager.from(application).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun authenticate(): Boolean =
        withContext(Dispatchers.Main.immediate) {
            operation.withLock {
                val activity = checkNotNull(owner.get()) { "No active authentication host." }
                check(prompt == null) { "Authentication is already active." }
                try {
                    suspendCancellableCoroutine { continuation ->
                        pending = continuation
                        val authentication =
                            BiometricPrompt(
                                activity,
                                ContextCompat.getMainExecutor(application),
                                object : BiometricPrompt.AuthenticationCallback() {
                                    override fun onAuthenticationSucceeded(
                                        result: BiometricPrompt.AuthenticationResult
                                    ) {
                                        if (continuation.isActive) continuation.resume(true)
                                    }

                                    override fun onAuthenticationError(
                                        errorCode: Int,
                                        errString: CharSequence,
                                    ) {
                                        if (!continuation.isActive) return
                                        if (
                                            errorCode in
                                                setOf(
                                                    BiometricPrompt.ERROR_USER_CANCELED,
                                                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                                    BiometricPrompt.ERROR_CANCELED,
                                                )
                                        )
                                            continuation.resume(false)
                                        else
                                            continuation.resumeWithException(
                                                AndroidDeviceAuthenticationException(errorCode)
                                            )
                                    }
                                },
                            )
                        prompt = authentication
                        val info =
                            BiometricPrompt.PromptInfo.Builder()
                                .setTitle(
                                    application.getString(
                                        dev.fajar.fluent.core.security.data.R.string
                                            .device_auth_title
                                    )
                                )
                                .setAllowedAuthenticators(authenticators)
                        info.setNegativeButtonText(application.getString(android.R.string.cancel))
                        authentication.authenticate(info.build())
                    }
                } finally {
                    prompt = null
                    pending = null
                }
            }
        }
}
