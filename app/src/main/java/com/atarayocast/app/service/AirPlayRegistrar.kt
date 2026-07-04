package com.atarayocast.app.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.atarayocast.app.bridge.NativeBridge
import java.util.HashMap

/**
 * Registers AirPlay RAOP and AirPlay mDNS services via Android's NsdManager.
 *
 * The native UxPlay engine builds TXT records in memory (android_dnssd_shim.c);
 * this class reads them via [NativeBridge] and registers the actual mDNS services
 * so that macOS / iOS devices can discover this Android device on the network.
 *
 * Two services are registered:
 * 1. _raop._tcp    — RAOP (audio streaming + mirror protocol)
 * 2. _airplay._tcp  — AirPlay (video / connection info)
 */
class AirPlayRegistrar(
    private val context: Context,
    private val bridge: NativeBridge
) {
    companion object {
        private const val TAG = "AirPlayRegistrar"
        private const val RAOP_SERVICE_TYPE = "_raop._tcp."
        private const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp."
        private const val MAX_REGISTRATION_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    private val nsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var raopRegistered = false
    private var airplayRegistered = false
    private var raopListener: NsdManager.RegistrationListener? = null
    private var airplayListener: NsdManager.RegistrationListener? = null

    /** Result callback for registration. */
    var onRegistered: (() -> Unit)? = null
    var onRegistrationFailed: ((String) -> Unit)? = null

    /**
     * Acquire multicast lock and register both mDNS services.
     * Must be called after [NativeBridge.start] has succeeded.
     */
    fun register(port: Int) {
        acquireMulticastLock()

        val raopName = bridge.getRaopServiceName()
        val serverName = bridge.getServerName()
        val raopTxt = bridge.getRaopTxtRecords()
        val airplayTxt = bridge.getAirplayTxtRecords()

        Log.i(TAG, "Registering mDNS services:")
        Log.i(TAG, "  RAOP: name='$raopName', type='$RAOP_SERVICE_TYPE', port=$port")
        Log.i(TAG, "  AirPlay: name='$serverName', type='$AIRPLAY_SERVICE_TYPE', port=$port")
        Log.i(TAG, "  RAOP TXT: $raopTxt")
        Log.i(TAG, "  AirPlay TXT: $airplayTxt")

        // Register RAOP service
        registerService(
            serviceName = raopName ?: "AirCast-RAOP",
            serviceType = RAOP_SERVICE_TYPE,
            port = port,
            txtRecords = raopTxt,
            isRaop = true
        )

        // Register AirPlay service (same port for HTTPD)
        registerService(
            serviceName = serverName ?: "AirCast",
            serviceType = AIRPLAY_SERVICE_TYPE,
            port = port,
            txtRecords = airplayTxt,
            isRaop = false
        )
    }

    private fun registerService(
        serviceName: String,
        serviceType: String,
        port: Int,
        txtRecords: HashMap<String, String>?,
        isRaop: Boolean
    ) {
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = serviceType
            this.port = port
            // Set TXT record attributes
            txtRecords?.forEach { (key, value) ->
                setAttribute(key, value)
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                val svcName = serviceInfo.serviceName
                Log.i(TAG, "Service registered: $svcName (${if (isRaop) "RAOP" else "AirPlay"})")
                if (isRaop) {
                    raopRegistered = true
                } else {
                    airplayRegistered = true
                }
                if (raopRegistered && airplayRegistered) {
                    onRegistered?.invoke()
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                val svcKey = if (isRaop) "RAOP" else "AirPlay"
                Log.e(TAG, "$svcKey registration failed: ${serviceInfo.serviceName}, error=$errorCode")
                registerServiceWithRetry(
                    serviceName = serviceInfo.serviceName,
                    serviceType = serviceInfo.serviceType,
                    port = serviceInfo.port,
                    txtRecords = txtRecords,
                    isRaop = isRaop,
                    attempt = 1
                )
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Service unregistered: ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Unregistration failed: ${serviceInfo.serviceName}, error=$errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            if (isRaop) {
                raopListener = listener
            } else {
                airplayListener = listener
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception registering ${if (isRaop) "RAOP" else "AirPlay"} service", e)
            onRegistrationFailed?.invoke(e.message ?: "Registration exception")
        }
    }

    /** Retry mDNS registration with exponential backoff */
    private fun registerServiceWithRetry(
        serviceName: String, serviceType: String, port: Int,
        txtRecords: HashMap<String, String>?, isRaop: Boolean, attempt: Int
    ) {
        if (attempt > MAX_REGISTRATION_RETRIES) {
            Log.e(TAG, "${if (isRaop) "RAOP" else "AirPlay"} registration failed after $MAX_REGISTRATION_RETRIES attempts")
            onRegistrationFailed?.invoke(
                "Failed to register ${if (isRaop) "RAOP" else "AirPlay"} after $MAX_REGISTRATION_RETRIES retries"
            )
            return
        }

        val delay = RETRY_DELAY_MS * attempt.toLong()
        Log.i(TAG, "Retrying ${if (isRaop) "RAOP" else "AirPlay"} registration in ${delay}ms (attempt $attempt)")
        // Use a daemon thread for simplicity — this runs within the service lifecycle
        Thread({
            Thread.sleep(delay)
            registerService(serviceName, serviceType, port, txtRecords, isRaop)
        }, "mDNS-Retry").apply { isDaemon = true }.start()
    }

    /** Release multicast lock and unregister mDNS services. */
    fun unregister() {
        // Unregister RAOP service
        raopListener?.let {
            try {
                nsdManager.unregisterService(it)
                Log.i(TAG, "RAOP service unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister RAOP service", e)
            }
        }
        raopListener = null

        // Unregister AirPlay service
        airplayListener?.let {
            try {
                nsdManager.unregisterService(it)
                Log.i(TAG, "AirPlay service unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister AirPlay service", e)
            }
        }
        airplayListener = null

        raopRegistered = false
        airplayRegistered = false
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("AirCast-mDNS").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "Multicast lock acquired")
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Multicast lock released")
            }
        }
        multicastLock = null
    }
}
