package dev.matinzd.airplaytv

import android.content.Context

/** User settings, plus the devices remembered after PIN pairing. */
class ReceiverSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Name shown in the AirPlay list; null = the TV's own device name. */
    var name: String?
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_NAME, value?.trim()?.takeIf { it.isNotEmpty() }).apply()

    /** New devices must enter a PIN shown on the TV (once; then they're remembered). */
    var requirePin: Boolean
        get() = prefs.getBoolean(KEY_PIN, false)
        set(value) = prefs.edit().putBoolean(KEY_PIN, value).apply()

    /** Offer 4K / H.265 mirroring to senders that support it (4K only on a 4K display). */
    var allow4k: Boolean
        get() = prefs.getBoolean(KEY_4K, true)
        set(value) = prefs.edit().putBoolean(KEY_4K, value).apply()

    /** Mirroring: show video as early as possible instead of waiting for audio (no lip-sync). */
    var lowLatency: Boolean
        get() = prefs.getBoolean(KEY_LOW_LATENCY, false)
        set(value) = prefs.edit().putBoolean(KEY_LOW_LATENCY, value).apply()

    /** Remembered devices: public key (base64) -> name. */
    val rememberedDevices: Map<String, String>
        get() = prefs.getStringSet(KEY_DEVICES, emptySet())!!.mapNotNull { entry ->
            val sep = entry.indexOf('|')
            if (sep > 0) entry.substring(0, sep) to entry.substring(sep + 1) else null
        }.toMap()

    fun isRemembered(publicKey: String) = rememberedDevices.containsKey(publicKey)

    fun remember(publicKey: String, name: String) {
        val devices = rememberedDevices.toMutableMap()
        devices[publicKey] = name
        prefs.edit().putStringSet(KEY_DEVICES, devices.map { "${it.key}|${it.value}" }.toSet()).apply()
    }

    fun forgetAllDevices() = prefs.edit().remove(KEY_DEVICES).apply()

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_PIN = "require_pin"
        const val KEY_4K = "allow_4k"
        const val KEY_LOW_LATENCY = "low_latency"
        const val KEY_DEVICES = "remembered_devices"
    }
}
