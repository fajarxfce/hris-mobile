@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.fajar.starter.location.data.datasources

import dev.fajar.starter.location.data.dto.*
import dev.fajar.starter.location.data.errors.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.useContents
import kotlinx.coroutines.*
import platform.CoreLocation.*
import platform.Foundation.*
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject

/** Foreground one-shot acquisition. The host closes this source with its container. */
class AppleLocationDataSource(private val manager: CLLocationManager = CLLocationManager()) :
    NSObject(), LocationDataSource, CLLocationManagerDelegateProtocol {
    private var permission: CancellableContinuation<Unit>? = null
    private var fix: CancellableContinuation<LocationFixDto>? = null

    init {
        manager.delegate = this
    }

    override suspend fun access(): LocationAccessDto =
        withContext(Dispatchers.Main) {
            LocationAccessDto(
                when (CLLocationManager.authorizationStatus()) {
                    kCLAuthorizationStatusAuthorizedAlways,
                    kCLAuthorizationStatusAuthorizedWhenInUse -> LocationPermissionDto.Granted
                    kCLAuthorizationStatusDenied,
                    kCLAuthorizationStatusRestricted -> LocationPermissionDto.PermanentlyDenied
                    else -> LocationPermissionDto.Denied
                },
                CLLocationManager.locationServicesEnabled(),
            )
        }

    override suspend fun requestPermission(): LocationAccessDto =
        withContext(Dispatchers.Main) {
            if (manager.delegate == null) throw LocationUnavailableException()
            if (permission != null) throw LocationBusyException()
            if (CLLocationManager.authorizationStatus() == kCLAuthorizationStatusNotDetermined) {
                suspendCancellableCoroutine { continuation ->
                    permission = continuation
                    manager.requestWhenInUseAuthorization()
                }
            }
            access()
        }

    override fun locationManager(
        manager: CLLocationManager,
        didChangeAuthorizationStatus: CLAuthorizationStatus,
    ) {
        if (didChangeAuthorizationStatus != kCLAuthorizationStatusNotDetermined) {
            val awaiting = permission
            permission = null
            awaiting?.takeIf { it.isActive }?.resume(Unit)
        }
    }

    override suspend fun current(): LocationFixDto =
        withContext(Dispatchers.Main) {
            if (manager.delegate == null) throw LocationUnavailableException()
            if (fix != null) throw LocationBusyException()
            try {
                suspendCancellableCoroutine { continuation ->
                    fix = continuation
                    manager.desiredAccuracy = kCLLocationAccuracyBest
                    manager.startUpdatingLocation()
                }
            } finally {
                manager.stopUpdatingLocation()
                fix = null
            }
        }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
        if (location.horizontalAccuracy < 0) return
        val awaiting = fix?.takeIf { it.isActive } ?: return
        val coordinates = location.coordinate.useContents { latitude to longitude }
        awaiting.resume(
            LocationFixDto(
                coordinates.first,
                coordinates.second,
                location.horizontalAccuracy,
                (location.timestamp.timeIntervalSince1970 * 1000).toLong(),
                location.sourceInformation?.isSimulatedBySoftware() == true,
            )
        )
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        val awaiting = fix
        awaiting
            ?.takeIf { it.isActive }
            ?.resumeWithException(
                if (didFailWithError.code == kCLErrorDenied) LocationPermissionException()
                else LocationUnavailableException()
            )
    }

    override suspend fun openSettings(target: LocationSettingsDto): Boolean =
        withContext(Dispatchers.Main) {
            val url = NSURL(string = UIApplicationOpenSettingsURLString)
            suspendCancellableCoroutine { continuation ->
                UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>()) { opened ->
                    if (continuation.isActive) continuation.resume(opened)
                }
            }
        }

    override fun close() {
        manager.stopUpdatingLocation()
        manager.delegate = null
        permission?.cancel()
        permission = null
        fix?.cancel()
        fix = null
    }
}
