package com.kyuusanq3.btbooter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
/**
 * Attaches this app to the car hotspot. On Android 10 and later the platform keeps that network
 * local to the requesting app and shows a system prompt before connecting. A suggestion is also
 * saved so the system can remember the hotspot.
 */
internal class WifiHotspotJoiner(context: Context) : HotspotJoiner {
    private val appContext = context.applicationContext
    private val suggestions = ArrayList<Any>()

    @Volatile
    private var resultLine: String? = null

    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(credentials: WirelessCredentials) {
        unregister()
        resultLine = null
        val request = hotspotRequest(credentials)
        if (request == null) {
            resultLine = "the car's Wi-Fi message had no SSID"
            return
        }
        if (request.key == HotspotKey.WEP) {
            resultLine = "security mode ${request.securityMode} is WEP, which this app does not join"
            return
        }
        if (!canJoin()) {
            resultLine = "Wi-Fi permission is missing, so \"${request.ssid}\" was not joined"
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            suggest(request)
            requestSpecifier(request)
        } else {
            joinLegacy(request)
        }
    }

    override fun result(): String? = resultLine

    override fun cancel() {
        if (resultLine != null) return
        unregister()
    }

    private fun canJoin(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return fine || granted(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return fine
    }

    private fun granted(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun suggest(request: HotspotRequest) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return
        val builder = WifiNetworkSuggestion.Builder().setSsid(request.ssid)
        request.bssid?.let { bssid ->
            runCatching { builder.setBssid(MacAddress.fromString(bssid)) }
        }
        if (request.key == HotspotKey.WPA2) {
            val phrase = request.passphrase ?: return
            runCatching { builder.setWpa2Passphrase(phrase) }.onFailure { return }
        }
        val suggestion = runCatching { builder.build() }.getOrElse {
            return
        }
        if (suggestions.isNotEmpty()) {
            wifi.removeNetworkSuggestions(suggestions.filterIsInstance<WifiNetworkSuggestion>())
            suggestions.clear()
        }
        val status = wifi.addNetworkSuggestions(listOf(suggestion))
        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ||
            status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
        ) {
            suggestions.add(suggestion)
        }
    }

    private fun requestSpecifier(request: HotspotRequest) {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        if (connectivity == null) {
            resultLine = "Wi-Fi service is missing"
            return
        }
        val specifier = runCatching { specifier(request, useBssid = request.bssid != null) }.getOrElse {
            resultLine = "could not describe \"${request.ssid}\": ${it.message}"
            return
        }
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val joined = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivity.bindProcessToNetwork(network)
                resultLine = "joined \"${request.ssid}\""
            }

            override fun onUnavailable() {
                resultLine = "the system did not attach \"${request.ssid}\""
            }
        }
        callback = joined
        runCatching { connectivity.requestNetwork(networkRequest, joined) }.onFailure {
            callback = null
            resultLine = "could not request \"${request.ssid}\": ${it.message}"
        }
    }

    private fun specifier(request: HotspotRequest, useBssid: Boolean): WifiNetworkSpecifier {
        val builder = WifiNetworkSpecifier.Builder().setSsid(request.ssid)
        if (useBssid) {
            request.bssid?.let { builder.setBssid(MacAddress.fromString(it)) }
        }
        if (request.key == HotspotKey.WPA2) {
            request.passphrase?.let(builder::setWpa2Passphrase)
        }
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun joinLegacy(request: HotspotRequest) {
        val wifi = appContext.getSystemService(WifiManager::class.java)
        if (wifi == null) {
            resultLine = "Wi-Fi service is missing"
            return
        }
        val config = android.net.wifi.WifiConfiguration()
        config.SSID = "\"${request.ssid}\""
        if (request.passphrase == null) {
            config.allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
        } else {
            config.preSharedKey = "\"${request.passphrase}\""
        }
        val id = wifi.addNetwork(config)
        if (id == -1) {
            resultLine = "the system rejected \"${request.ssid}\""
            return
        }
        val enabled = wifi.enableNetwork(id, true)
        wifi.reconnect()
        resultLine = if (enabled) "joined \"${request.ssid}\"" else "could not enable \"${request.ssid}\""
    }

    private fun unregister() {
        val current = callback ?: return
        callback = null
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { connectivity.unregisterNetworkCallback(current) }
    }
}
