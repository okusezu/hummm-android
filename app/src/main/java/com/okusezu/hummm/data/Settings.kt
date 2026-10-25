package com.okusezu.hummm.data

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persistent settings (the Android counterpart of ~/.config/hummm/config.json). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("hummm", Context.MODE_PRIVATE)

    private fun <T> pref(initial: T, write: (T) -> Unit): Pair<MutableStateFlow<T>, (T) -> Unit> {
        val flow = MutableStateFlow(initial)
        return flow to { v: T -> flow.value = v; write(v) }
    }

    private val _quality = pref(prefs.getString("quality", "high")!!.takeIf { it in Streams.QUALITIES } ?: "high") {
        prefs.edit().putString("quality", it).apply()
    }
    val quality: StateFlow<String> = _quality.first.asStateFlow()
    fun setQuality(q: String) = _quality.second(q)

    private val _metric = pref(prefs.getString("metric", "similar artists")!!.takeIf { it in METRICS } ?: "similar artists") {
        prefs.edit().putString("metric", it).apply()
    }
    val metric: StateFlow<String> = _metric.first.asStateFlow()
    fun setMetric(m: String) = _metric.second(m)

    private val _autoDiscover = pref(prefs.getBoolean("auto_discover", false)) {
        prefs.edit().putBoolean("auto_discover", it).apply()
    }
    val autoDiscover: StateFlow<Boolean> = _autoDiscover.first.asStateFlow()
    fun setAutoDiscover(on: Boolean) = _autoDiscover.second(on)

    /** Show the cover beside the track in the player (the desktop's "a"). */
    private val _art = pref(prefs.getBoolean("art", false)) {
        prefs.edit().putBoolean("art", it).apply()
    }
    val art: StateFlow<Boolean> = _art.first.asStateFlow()
    fun setArt(on: Boolean) = _art.second(on)

    /** The visualiser style last swiped to (a VisStyle name). */
    private val _visualStyle = pref(prefs.getString("visual_style", "cava")!!) {
        prefs.edit().putString("visual_style", it).apply()
    }
    val visualStyle: StateFlow<String> = _visualStyle.first.asStateFlow()
    fun setVisualStyle(name: String) = _visualStyle.second(name)

    /** A Sonos speaker's IP address entered by hand, for networks where searching doesn't find them. */
    private val _sonosHost = pref(prefs.getString("sonos_host", null)) {
        prefs.edit().putString("sonos_host", it).apply()
    }
    val sonosHost: StateFlow<String?> = _sonosHost.first.asStateFlow()
    fun setSonosHost(host: String?) = _sonosHost.second(host)

    /** Equalizer band levels in millibels, one per device band (empty = flat). */
    private val _eq = pref(prefs.getString("eq", "")!!.split(',').mapNotNull { it.toIntOrNull() }) {
        prefs.edit().putString("eq", it.joinToString(",")).apply()
    }
    val eq: StateFlow<List<Int>> = _eq.first.asStateFlow()
    fun setEq(levels: List<Int>) = _eq.second(levels)

    private val _cookie = pref(prefs.getString("cookie", null)) {
        prefs.edit().putString("cookie", it).apply()
    }
    val cookie: StateFlow<String?> = _cookie.first.asStateFlow()

    private val _account = pref(prefs.getString("account", null)) {
        prefs.edit().putString("account", it).apply()
    }
    val account: StateFlow<String?> = _account.first.asStateFlow()

    fun signIn(cookie: String, account: String) {
        _cookie.second(cookie)
        _account.second(account)
    }

    fun signOut() {
        _cookie.second(null)
        _account.second(null)
    }

    val language: String = Locale.getDefault().language.ifEmpty { "en" }
}
