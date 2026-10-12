package dev.fajar.starter.fileio

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.io.FileNotFoundException
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

/** A save dialog grants access to one output. No blanket storage permission or path deletion. */
class AndroidFileOutputSource(context: Context) : FileOutputSource {
    private val resolver = context.applicationContext.contentResolver
    private val outputs = FileHandleRegistry<JvmFileOutput>()
    private var owner: WeakReference<ComponentActivity>? = null

    fun attach(activity: ComponentActivity) {
        if (owner?.get() !== activity) {
            outputs.cancelAcquisitions()
            owner = WeakReference(activity)
        }
    }

    fun detach(activity: ComponentActivity) {
        if (owner?.get() === activity) {
            owner = null
            outputs.cancelAcquisitions()
        }
    }

    override suspend fun select(
        requestId: String,
        fileName: String,
        mediaType: String,
        size: Long,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            validateFileOutput(fileName, mediaType, size)
            val activity = owner?.get() ?: throw FileUnavailableException()
            val ticket = outputs.reserve(requestId, currentCoroutineContext().job)
            var launcher: ActivityResultLauncher<String>? = null
            var retained = false
            try {
                val uri =
                    suspendCancellableCoroutine<Uri?> { continuation ->
                        try {
                            launcher =
                                activity.activityResultRegistry.register(
                                    "file-output-$requestId",
                                    ActivityResultContracts.CreateDocument(mediaType),
                                ) { uri ->
                                    if (continuation.isActive) continuation.resume(uri)
                                }
                            launcher.launch(fileName)
                        } catch (cause: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(cause)
                        }
                    } ?: return@withContext false
                androidProviderCall { signal ->
                    val descriptor =
                        resolver.openFileDescriptor(uri, "w", signal)
                            ?: throw FileNotFoundException()
                    val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
                    val output = JvmFileOutput(ticket, size, stream.channel, stream, stream::flush)
                    try {
                        // Register before delivering a detached result. Lost/cancelled results
                        // are then closed by this request's finally, never left between owners.
                        outputs.publish(requestId, ticket, output)
                    } catch (cause: Throwable) {
                        output.closeAfterFailure(cause)
                        throw cause
                    }
                }
                retained = true
                true
            } finally {
                launcher?.unregister()
                if (!retained) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        outputs.discard(requestId, ticket)?.close()
                    }
                }
            }
        }

    override suspend fun write(requestId: String, offset: Long, bytes: ByteArray) =
        withContext(Dispatchers.IO) {
            val operation = currentCoroutineContext().job
            val output = outputs.beginOperation(requestId, operation)
            try {
                output.write(offset, bytes)
            } catch (cause: Throwable) {
                outputs.discard(requestId, output.ticket)
                output.closeAfterFailure(cause)
                throw cause
            } finally {
                outputs.endOperation(requestId, operation)
            }
        }

    override suspend fun complete(requestId: String) =
        withContext(Dispatchers.IO) {
            val operation = currentCoroutineContext().job
            val output = outputs.beginOperation(requestId, operation)
            try {
                output.complete()
                output.close()
            } catch (cause: Throwable) {
                output.closeAfterFailure(cause)
                throw cause
            } finally {
                outputs.discard(requestId, output.ticket)
            }
        }

    override suspend fun release(requestId: String) =
        withContext(Dispatchers.IO) {
            outputs.release(requestId)?.close()
            Unit
        }

    override fun close() {
        owner = null
        closeFileOutputs(outputs.close())
    }
}
