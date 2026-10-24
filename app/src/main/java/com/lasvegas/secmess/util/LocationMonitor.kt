package com.lasvegas.secmess.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.*
import com.lasvegas.secmess.crypto.KeyStoreHelper
import com.lasvegas.secmess.model.UnsafePlacesRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Localização só com a app em primeiro plano (MainActivity.onResume -> onPause).
 * O serviço de mensagens já não arranca o GPS em background: em idle não há
 * atualizações de localização, câmara nem microfone.
 */
object LocationMonitor {
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    private val _isInsideUnsafeZone = MutableStateFlow(false)
    val isInsideUnsafeZone = _isInsideUnsafeZone.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation = _currentLocation.asStateFlow()

    private var insideCount = 0
    private val locationHistory = mutableListOf<Location>()

    @SuppressLint("MissingPermission")
    fun startMonitoring(context: Context) {
        // Idempotente: duas chamadas não duplicam callbacks (era o que acontecia com
        // MainActivity + RelayService).
        if (locationCallback != null) return
        if (fusedLocationClient == null) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000)
            .setMinUpdateIntervalMillis(10_000)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                _currentLocation.value = location

                val isInsideNow = UnsafePlacesRegistry.isInsideUnsafeZone(location.latitude, location.longitude)
                val helper = KeyStoreHelper(context)

                if (isInsideNow) {
                    insideCount++
                    locationHistory.add(location)
                    if (locationHistory.size > 3) locationHistory.removeAt(0)

                    // 3 confirmações consecutivas + movimento mínimo.
                    if (insideCount >= 3) {
                        val movedEnough = if (locationHistory.size >= 3) {
                            val dist1 = locationHistory[0].distanceTo(locationHistory[1])
                            val dist2 = locationHistory[1].distanceTo(locationHistory[2])
                            (dist1 + dist2) > 2.0
                        } else true

                        if (movedEnough) {
                            _isInsideUnsafeZone.value = true
                            val durationMillis = 10 * 60 * 1000L
                            val newEndTime = System.currentTimeMillis() + durationMillis
                            if (newEndTime > helper.getStealthEndTime()) {
                                helper.setStealthEndTime(newEndTime)
                            }
                        }
                    }
                } else {
                    insideCount = 0
                    locationHistory.clear()
                    if (!helper.isStealthActive()) {
                        _isInsideUnsafeZone.value = false
                    }
                }
            }
        }
        locationCallback = cb

        try {
            fusedLocationClient?.requestLocationUpdates(request, cb, context.mainLooper)
        } catch (_: Exception) {
            locationCallback = null
        }
    }

    /** Para o GPS quando a app sai de primeiro plano. */
    fun stopMonitoring() {
        val cb = locationCallback ?: return
        try {
            fusedLocationClient?.removeLocationUpdates(cb)
        } catch (_: Exception) {
        }
        locationCallback = null
        insideCount = 0
        locationHistory.clear()
    }
}
