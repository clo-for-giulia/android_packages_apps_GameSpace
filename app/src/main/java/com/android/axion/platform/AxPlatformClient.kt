/*
 * Copyright (C) 2025-2026 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.axion.platform

import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

class AxPlatformClient private constructor() {

    abstract class Listener {
        open fun onFeatureChanged(feature: String, active: Boolean) {}
        open fun onStateChanged(key: String, state: Bundle) {}
    }

    private var appContext: Context? = null
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val handler = Handler(Looper.getMainLooper())

    private var isTorchOn = false
    private var torchCameraId: String? = null

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext ?: context
        appContext = app

        try {
            val cameraManager = app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager != null) {
                for (id in cameraManager.cameraIdList) {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    if (flashAvailable && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        torchCameraId = id
                        break
                    }
                }
                cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId == torchCameraId) {
                            isTorchOn = enabled
                            notifyFeatureChanged(FEATURE_FLASHLIGHT, enabled)
                        }
                    }
                }, handler)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register torch callback", e)
        }

        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        notifyFeatureChanged(FEATURE_WIFI, isWifiActive())
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        notifyFeatureChanged(FEATURE_BLUETOOTH, isBluetoothActive())
                    }
                    NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                        notifyFeatureChanged(FEATURE_ZEN, isZenActive())
                    }
                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                        notifyFeatureChanged(FEATURE_AIRPLANE_MODE, isAirplaneActive())
                    }
                    LocationManager.MODE_CHANGED_ACTION -> {
                        notifyFeatureChanged(FEATURE_LOCATION, isLocationActive())
                    }
                }
            }
        }
        try {
            app.registerReceiver(receiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register broadcast receiver", e)
        }

        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                notifyFeatureChanged(FEATURE_ROTATION, isRotationActive())
                notifyFeatureChanged(FEATURE_HEADS_UP, isHeadsUpActive())
                notifyFeatureChanged(FEATURE_NIGHT_LIGHT, isNightLightActive())
                notifyFeatureChanged(FEATURE_AOD, isAodActive())
            }
        }
        try {
            app.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                false, observer
            )
            app.contentResolver.registerContentObserver(
                Settings.Global.getUriFor("heads_up_notifications_enabled"),
                false, observer
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register content observer", e)
        }
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun getState(feature: String): Bundle {
        val bundle = Bundle()
        val active = isFeatureActive(feature)
        bundle.putBoolean("active", active)
        return bundle
    }

    fun toggle(feature: String) {
        val context = appContext ?: return
        try {
            when (feature) {
                FEATURE_WIFI -> {
                    val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    wm?.let {
                        @Suppress("DEPRECATION")
                        val next = !it.isWifiEnabled
                        @Suppress("DEPRECATION")
                        it.isWifiEnabled = next
                        notifyFeatureChanged(feature, next)
                    }
                }
                FEATURE_BLUETOOTH -> {
                    val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val adapter = bm?.adapter
                    if (adapter != null) {
                        val willEnable = !adapter.isEnabled
                        if (willEnable) adapter.enable() else adapter.disable()
                        notifyFeatureChanged(feature, willEnable)
                    }
                }
                FEATURE_ZEN -> {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    if (nm != null) {
                        val current = nm.currentInterruptionFilter
                        val isZen = current != NotificationManager.INTERRUPTION_FILTER_ALL
                        val newFilter = if (isZen) {
                            NotificationManager.INTERRUPTION_FILTER_ALL
                        } else {
                            NotificationManager.INTERRUPTION_FILTER_PRIORITY
                        }
                        nm.setInterruptionFilter(newFilter)
                        notifyFeatureChanged(feature, !isZen)
                    }
                }
                FEATURE_ROTATION -> {
                    val current = Settings.System.getInt(
                        context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1
                    ) == 1
                    val newTarget = if (current) 0 else 1
                    Settings.System.putInt(
                        context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, newTarget
                    )
                    notifyFeatureChanged(feature, newTarget == 1)
                }
                FEATURE_MOBILE_DATA -> {
                    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    if (tm != null) {
                        val current = tm.isDataEnabled
                        tm.setDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER, !current)
                        notifyFeatureChanged(feature, !current)
                    }
                }
                FEATURE_AIRPLANE_MODE -> {
                    val current = Settings.Global.getInt(
                        context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0
                    ) == 1
                    val newTarget = if (current) 0 else 1
                    Settings.Global.putInt(
                        context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, newTarget
                    )
                    context.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                        putExtra("state", newTarget == 1)
                    })
                    notifyFeatureChanged(feature, newTarget == 1)
                }
                FEATURE_FLASHLIGHT -> {
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    val camId = torchCameraId
                    if (cm != null && camId != null) {
                        val newTarget = !isTorchOn
                        try {
                            cm.setTorchMode(camId, newTarget)
                            isTorchOn = newTarget
                            notifyFeatureChanged(feature, newTarget)
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Error toggling flashlight", e)
                        }
                    }
                }
                FEATURE_DARK_MODE -> {
                    val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
                    uiMode?.let {
                        val isNight = it.nightMode == UiModeManager.MODE_NIGHT_YES
                        val newMode = if (isNight) UiModeManager.MODE_NIGHT_NO else UiModeManager.MODE_NIGHT_YES
                        it.setNightMode(newMode)
                        notifyFeatureChanged(feature, !isNight)
                    }
                }
                FEATURE_LOCATION -> {
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    lm?.let {
                        val current = it.isLocationEnabled
                        it.setLocationEnabledForUser(!current, UserHandle.of(UserHandle.myUserId()))
                        notifyFeatureChanged(feature, !current)
                    }
                }
                FEATURE_HOTSPOT -> {
                    val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
                FEATURE_NFC -> {
                    val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
                    if (nfcAdapter != null) {
                        val enabled = nfcAdapter.isEnabled
                        try {
                            if (enabled) {
                                nfcAdapter.javaClass.getMethod("disable").invoke(nfcAdapter)
                            } else {
                                nfcAdapter.javaClass.getMethod("enable").invoke(nfcAdapter)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to toggle NFC", e)
                        }
                        notifyFeatureChanged(feature, !enabled)
                    }
                }
                FEATURE_HEADS_UP -> {
                    val current = Settings.Global.getInt(
                        context.contentResolver, "heads_up_notifications_enabled", 1
                    ) == 1
                    val newTarget = if (current) 0 else 1
                    Settings.Global.putInt(
                        context.contentResolver, "heads_up_notifications_enabled", newTarget
                    )
                    notifyFeatureChanged(feature, newTarget == 1)
                }
                FEATURE_SCREENSHOT -> {
                    try {
                        val intent = Intent("com.android.internal.intent.action.GLOBAL_ACTION_SCREENSHOT")
                        context.sendBroadcast(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to trigger screenshot", e)
                    }
                }
                FEATURE_SCREEN_RECORD -> {
                    val intent = Intent("com.android.systemui.screenrecord.START").apply {
                        setPackage("com.android.systemui")
                    }
                    try {
                        context.startForegroundService(intent)
                    } catch (e: Exception) {
                        try {
                            context.sendBroadcast(intent)
                        } catch (_: Exception) {}
                    }
                }
                else -> {
                    Log.d(TAG, "Toggle requested for feature: $feature")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling feature: $feature", e)
        }
    }

    private fun isFeatureActive(feature: String): Boolean {
        return when (feature) {
            FEATURE_WIFI -> isWifiActive()
            FEATURE_BLUETOOTH -> isBluetoothActive()
            FEATURE_ZEN -> isZenActive()
            FEATURE_ROTATION -> isRotationActive()
            FEATURE_MOBILE_DATA -> isMobileDataActive()
            FEATURE_AIRPLANE_MODE -> isAirplaneActive()
            FEATURE_FLASHLIGHT -> isTorchOn
            FEATURE_DARK_MODE -> isDarkModeActive()
            FEATURE_LOCATION -> isLocationActive()
            FEATURE_HEADS_UP -> isHeadsUpActive()
            FEATURE_NFC -> isNfcActive()
            FEATURE_NIGHT_LIGHT -> isNightLightActive()
            FEATURE_AOD -> isAodActive()
            else -> false
        }
    }

    private fun isWifiActive(): Boolean {
        val wm = appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wm?.isWifiEnabled == true
    }

    private fun isBluetoothActive(): Boolean {
        val bm = appContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter?.isEnabled == true
    }

    private fun isZenActive(): Boolean {
        val nm = appContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return nm?.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun isRotationActive(): Boolean {
        val ctx = appContext ?: return false
        return Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
    }

    private fun isMobileDataActive(): Boolean {
        val tm = appContext?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return tm?.isDataEnabled == true
    }

    private fun isAirplaneActive(): Boolean {
        val ctx = appContext ?: return false
        return Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }

    private fun isDarkModeActive(): Boolean {
        val uiMode = appContext?.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiMode?.nightMode == UiModeManager.MODE_NIGHT_YES
    }

    private fun isLocationActive(): Boolean {
        val lm = appContext?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return lm?.isLocationEnabled == true
    }

    private fun isHeadsUpActive(): Boolean {
        val ctx = appContext ?: return false
        return Settings.Global.getInt(ctx.contentResolver, "heads_up_notifications_enabled", 1) == 1
    }

    private fun isNfcActive(): Boolean {
        val ctx = appContext ?: return false
        return NfcAdapter.getDefaultAdapter(ctx)?.isEnabled == true
    }

    private fun isNightLightActive(): Boolean {
        val ctx = appContext ?: return false
        return Settings.Secure.getIntForUser(
            ctx.contentResolver, "night_display_activated", 0, UserHandle.USER_CURRENT
        ) == 1
    }

    private fun isAodActive(): Boolean {
        val ctx = appContext ?: return false
        return Settings.Secure.getIntForUser(
            ctx.contentResolver, "doze_always_on", 0, UserHandle.USER_CURRENT
        ) == 1
    }

    private fun notifyFeatureChanged(feature: String, active: Boolean) {
        val state = Bundle().apply {
            putBoolean("active", active)
        }
        handler.post {
            for (listener in listeners) {
                listener.onFeatureChanged(feature, active)
                listener.onStateChanged(feature, state)
            }
        }
    }

    companion object {
        private const val TAG = "AxPlatformClient"

        @Volatile
        private var instance: AxPlatformClient? = null

        @JvmStatic
        fun getInstance(): AxPlatformClient {
            return instance ?: synchronized(this) {
                instance ?: AxPlatformClient().also { instance = it }
            }
        }

        @JvmStatic
        fun getLabel(state: Bundle): String? {
            return state.getString("label")
        }

        const val FEATURE_WIFI = "wifi"
        const val FEATURE_BLUETOOTH = "bluetooth"
        const val FEATURE_ZEN = "zen"
        const val FEATURE_ROTATION = "rotation"
        const val FEATURE_MOBILE_DATA = "cell"
        const val FEATURE_AIRPLANE_MODE = "airplane"
        const val FEATURE_FLASHLIGHT = "flashlight"
        const val FEATURE_DARK_MODE = "dark_theme"
        const val FEATURE_LOCATION = "location"
        const val FEATURE_HOTSPOT = "hotspot"
        const val FEATURE_NFC = "nfc"
        const val FEATURE_NIGHT_LIGHT = "night_display"
        const val FEATURE_AOD = "doze"
        const val FEATURE_DATA_SAVER = "saver"
        const val FEATURE_REDUCE_BRIGHTNESS = "reduce_bright_colors"
        const val FEATURE_HEADS_UP = "heads_up"
        const val FEATURE_CAMERA_PRIVACY = "camera_toggle"
        const val FEATURE_MIC_PRIVACY = "mic_toggle"
        const val FEATURE_POWER_SHARE = "power_share"
        const val FEATURE_CAFFEINE = "caffeine"
        const val FEATURE_VPN = "vpn"
        const val FEATURE_CAST = "cast"
        const val FEATURE_SCREEN_RECORD = "record"
        const val FEATURE_SCREENSHOT = "screenshot"
    }
}
