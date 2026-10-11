package dev.fajar.starter.location.data.datasources

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import dev.fajar.starter.location.data.dto.*
import dev.fajar.starter.location.data.errors.*
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

/** The Activity owns permission registration; each caller owns its cancellable OS fix. */
class AndroidLocationDataSource(context: Context) : LocationDataSource {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)
    private val preferences =
        this.context.getSharedPreferences("location-permission", Context.MODE_PRIVATE)
    private var owner: WeakReference<ComponentActivity>? = null
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var permission: CancellableContinuation<Unit>? = null
    private var fix: CancellationSignal? = null
    private var fixResult: CancellableContinuation<LocationFixDto>? = null

    fun attach(activity: ComponentActivity) {
        close()
        val attached = WeakReference(activity)
        owner = attached
        launcher =
            activity.registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {
                if (owner === attached) {
                    val awaiting = permission
                    permission = null
                    awaiting?.takeIf { it.isActive }?.resume(Unit)
                }
            }
    }

    fun detach(activity: ComponentActivity) {
        if (owner?.get() === activity) close()
    }

    override suspend fun access(): LocationAccessDto =
        withContext(Dispatchers.Main.immediate) {
            val granted =
                arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                    .any {
                        ContextCompat.checkSelfPermission(context, it) ==
                            PackageManager.PERMISSION_GRANTED
                    }
            val activity = owner?.get()
            val deniedPermanently =
                !granted &&
                    preferences.getBoolean("requested", false) &&
                    activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
            LocationAccessDto(
                when {
                    granted -> LocationPermissionDto.Granted
                    deniedPermanently -> LocationPermissionDto.PermanentlyDenied
                    else -> LocationPermissionDto.Denied
                },
                LocationManagerCompat.isLocationEnabled(manager),
            )
        }

    override suspend fun requestPermission(): LocationAccessDto =
        withContext(Dispatchers.Main.immediate) {
            if (permission != null) throw LocationBusyException()
            val registered = launcher ?: throw LocationUnavailableException()
            preferences.edit().putBoolean("requested", true).apply()
            suspendCancellableCoroutine { continuation ->
                permission = continuation
                // Retain this slot after cancellation until the OS callback arrives. A late result
                // must never complete a newer permission request.
                try {
                    registered.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                } catch (error: Exception) {
                    permission = null
                    continuation.resumeWithException(error)
                }
            }
            access()
        }

    @Suppress("DEPRECATION")
    override suspend fun current(): LocationFixDto =
        withContext(Dispatchers.Main.immediate) {
            if (fix != null) throw LocationBusyException()
            val provider =
                manager.getBestProvider(
                    Criteria().apply { accuracy = Criteria.ACCURACY_FINE },
                    true,
                ) ?: throw LocationUnavailableException()
            val cancellation = CancellationSignal()
            fix = cancellation
            try {
                suspendCancellableCoroutine { continuation ->
                    fixResult = continuation
                    continuation.invokeOnCancellation { cancellation.cancel() }
                    LocationManagerCompat.getCurrentLocation(
                        manager,
                        provider,
                        cancellation,
                        ContextCompat.getMainExecutor(context),
                    ) { location ->
                        if (continuation.isActive) {
                            if (location == null)
                                continuation.resumeWithException(LocationUnavailableException())
                            else
                                continuation.resume(
                                    LocationFixDto(
                                        location.latitude,
                                        location.longitude,
                                        location.accuracy.toDouble(),
                                        location.time,
                                        LocationCompat.isMock(location),
                                    )
                                )
                        }
                    }
                }
            } catch (error: SecurityException) {
                throw LocationPermissionException(error)
            } finally {
                cancellation.cancel()
                if (fix === cancellation) {
                    fix = null
                    fixResult = null
                }
            }
        }

    override suspend fun openSettings(target: LocationSettingsDto): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val intent =
                if (target == LocationSettingsDto.Application)
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    )
                else Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }

    override fun close() {
        owner = null
        launcher?.unregister()
        launcher = null
        permission?.cancel()
        permission = null
        fix?.cancel()
        fix = null
        fixResult?.cancel()
        fixResult = null
    }
}
