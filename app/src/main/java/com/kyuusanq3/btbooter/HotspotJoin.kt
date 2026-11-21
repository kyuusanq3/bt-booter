package com.kyuusanq3.btbooter

/** How the car protected the hotspot in the type 3 message. WEP is recognized and not joined. */
internal enum class HotspotKey {
    OPEN,
    WEP,
    WPA2,
}

internal data class HotspotRequest(
    val ssid: String,
    val passphrase: String?,
    val bssid: String?,
    val securityMode: Int?,
    val key: HotspotKey,
)

/**
 * Joins the car hotspot from a type 3 Wi-Fi message. [result] stays null until the attempt finishes
 * so the Bluetooth loop can keep polling. [cancel] drops an attempt that is still waiting and leaves
 * a finished join attached.
 */
internal interface HotspotJoiner {
    fun start(credentials: WirelessCredentials)
    fun result(): String?
    fun cancel()
}

internal object NoHotspotJoiner : HotspotJoiner {
    override fun start(credentials: WirelessCredentials) = Unit
    override fun result(): String? = "Wi-Fi join is not available."
    override fun cancel() = Unit
}

/** Security mode 1 is open, 2 and 3 are WEP, and 8 (the T9E capture) is WPA2-Personal. */
internal fun hotspotKey(securityMode: Int?, password: String?): HotspotKey = when {
    password.isNullOrEmpty() || securityMode == SECURITY_OPEN -> HotspotKey.OPEN
    securityMode == SECURITY_WEP_64 || securityMode == SECURITY_WEP_128 -> HotspotKey.WEP
    else -> HotspotKey.WPA2
}

internal fun hotspotRequest(credentials: WirelessCredentials): HotspotRequest? {
    val ssid = credentials.ssid?.takeIf { it.isNotEmpty() } ?: return null
    val key = hotspotKey(credentials.securityMode, credentials.password)
    return HotspotRequest(
        ssid = ssid,
        passphrase = if (key == HotspotKey.OPEN) null else credentials.password,
        bssid = credentials.bssid?.takeIf { it.isNotEmpty() },
        securityMode = credentials.securityMode,
        key = key,
    )
}

private const val SECURITY_OPEN = 1
private const val SECURITY_WEP_64 = 2
private const val SECURITY_WEP_128 = 3
