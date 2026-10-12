package dev.fajar.starter.fileio

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.io.FileNotFoundException
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** SAF grants belong to the selected input; no blanket storage permission is requested. */
class AndroidFileInputSource(context: Context) : FileInputSource {
    private val resolver = context.applicationContext.contentResolver
    private val inputs = FileInputRegistry<Uri>()
    private var owner: WeakReference<ComponentActivity>? = null

    fun attach(activity: ComponentActivity) {
        if (owner?.get() !== activity) {
            inputs.cancelSelections()
            owner = WeakReference(activity)
        }
    }

    fun detach(activity: ComponentActivity) {
        if (owner?.get() === activity) {
            owner = null
            inputs.cancelSelections()
        }
    }

    override suspend fun select(requestId: String, mediaTypes: Set<String>): FileInputMetadata? =
        withContext(Dispatchers.Main.immediate) {
            validateFileMediaTypes(mediaTypes)
            val activity = owner?.get() ?: throw FileInputUnavailableException()
            val ticket = inputs.reserve(requestId, currentCoroutineContext().job)
            var launcher: ActivityResultLauncher<Array<String>>? = null
            var retained = false
            try {
                val uri =
                    suspendCancellableCoroutine<Uri?> { continuation ->
                        try {
                            launcher =
                                activity.activityResultRegistry.register(
                                    "file-input-$requestId",
                                    ActivityResultContracts.OpenDocument(),
                                ) { selected ->
                                    if (continuation.isActive) continuation.resume(selected)
                                }
                            launcher.launch(mediaTypes.toTypedArray())
                        } catch (error: Exception) {
                            continuation.resumeWithException(error)
                        }
                    } ?: return@withContext null
                val metadata = androidProviderCall { cancellation ->
                    resolver
                        .query(
                            uri,
                            arrayOf(
                                OpenableColumns.DISPLAY_NAME,
                                OpenableColumns.SIZE,
                                DocumentsContract.Document.COLUMN_MIME_TYPE,
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                            ),
                            null,
                            null,
                            null,
                            cancellation,
                        )
                        ?.use { cursor ->
                            if (!cursor.moveToFirst()) throw FileNotFoundException()
                            val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                            val type =
                                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                            val modified =
                                cursor.getColumnIndex(
                                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                                )
                            FileInputMetadata(
                                if (name < 0 || cursor.isNull(name)) null
                                else cursor.getString(name),
                                if (type < 0 || cursor.isNull(type)) null
                                else cursor.getString(type),
                                if (size < 0 || cursor.isNull(size)) null
                                else cursor.getLong(size).takeIf { it >= 0 },
                                if (modified < 0 || cursor.isNull(modified)) null
                                else cursor.getLong(modified).takeIf { it > 0 },
                            )
                        } ?: throw FileNotFoundException()
                }
                inputs.publish(requestId, ticket, uri)
                retained = true
                metadata
            } finally {
                launcher?.unregister()
                if (!retained) inputs.discardSelection(requestId, ticket)
            }
        }

    override fun read(requestId: String): Flow<ByteArray> =
        channelFlow {
                val operation = currentCoroutineContext().job
                val uri = inputs.beginRead(requestId, operation)
                val cancellation = CancellationSignal()
                val worker =
                    launch(Dispatchers.IO) {
                        val descriptor =
                            resolver.openFileDescriptor(uri, "r", cancellation)
                                ?: throw FileNotFoundException()
                        // Acquisition and use share this worker's finally. A descriptor never
                        // crosses a cancellable continuation on its way to the reader.
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                            readFileChannel(stream.channel).collect { send(it) }
                        }
                    }
                worker.invokeOnCompletion { inputs.endRead(requestId, operation) }
                try {
                    worker.join()
                } finally {
                    cancellation.cancel()
                }
            }
            .buffer(0)

    override suspend fun release(requestId: String) {
        inputs.release(requestId)
    }

    override fun close() {
        owner = null
        inputs.close()
    }
}
