package com.linktomac.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredMac(
    val host: String,
    val port: Int,
    val macDeviceId: String?,
    val macPublicKey: String?
)

private const val SERVICE_TYPE = "_linktomac._tcp"

/** Discovers the Mac app on the LAN via Bonjour/NSD, used to reconnect without rescanning a QR code. */
class MacDiscovery(private val context: Context) {

    fun discover(): Flow<DiscoveredMac> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("linktomac-nsd").apply {
            setReferenceCounted(true)
            acquire()
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val attrs = serviceInfo.attributes
                val macDeviceId = attrs["id"]?.let { String(it, Charsets.UTF_8) }
                val macPublicKey = attrs["pk"]?.let { String(it, Charsets.UTF_8) }
                val host = serviceInfo.host?.hostAddress ?: return
                trySend(DiscoveredMac(host, serviceInfo.port, macDeviceId, macPublicKey))
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            @Suppress("DEPRECATION")
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, resolveListener)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose {
            nsdManager.stopServiceDiscovery(discoveryListener)
            multicastLock.release()
        }
    }
}
