package app.recall.glasses.device

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Joins the phone's hotspot. The M6 has no usable Wi-Fi screen and blocks `cmd wifi` over adb,
 * so setup goes through the app. The password is never logged.
 */
@Suppress("DEPRECATION")
class WifiJoiner(context: Context) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val prefs = context.applicationContext.getSharedPreferences("hotspot", Context.MODE_PRIVATE)

    /**
     * Locks the glasses to the phone's hotspot. Android may auto-join another saved network, such
     * as the venue Wi-Fi, and in use there is no USB cable or Wi-Fi screen to fix it by hand.
     */
    fun ensureOnHotspot() {
        val ssid = prefs.getString(KEY_SSID, null) ?: return
        if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true
        val id = wifi.configuredNetworks.orEmpty()
            .firstOrNull { it.SSID == "\"$ssid\"" }?.networkId ?: return
        if (wifi.connectionInfo?.networkId == id) return
        Log.i(TAG, "Returning to the phone's hotspot")
        wifi.enableNetwork(id, true)
        wifi.reconnect()
    }

    fun join(ssid: String, password: String): Boolean {
        if (ssid.isEmpty() || password.length !in 8..63 || '"' in ssid || '"' in password) {
            Log.w(TAG, "Wi-Fi setup needs a name and an 8 to 63 character WPA2 password")
            return false
        }
        if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true
        val quoted = "\"$ssid\""
        wifi.configuredNetworks.orEmpty()
            .filter { it.SSID == quoted }
            .forEach { wifi.removeNetwork(it.networkId) }
        val config = WifiConfiguration().apply {
            SSID = quoted
            preSharedKey = "\"$password\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }
        val id = wifi.addNetwork(config)
        val joined = id >= 0 && wifi.enableNetwork(id, true) && wifi.reconnect()
        if (joined) {
            wifi.saveConfiguration()
            prefs.edit().putString(KEY_SSID, ssid).apply()
        }
        Log.i(TAG, "Wi-Fi setup for the hotspot ${if (joined) "started" else "failed"}")
        return joined
    }

    private companion object {
        const val TAG = "RecallWifi"
        const val KEY_SSID = "ssid"
    }
}
