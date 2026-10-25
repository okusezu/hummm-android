package com.okusezu.hummm.playback

import android.content.Context
import android.net.wifi.WifiManager
import com.okusezu.hummm.HummmApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Sonos as the UI sees it: the groups found on the network, and which one to
 * play on. The playback service watches [target] and moves the queue over.
 */
object Sonos {
    private val _groups = MutableStateFlow<List<SonosGroup>>(emptyList())
    val groups: StateFlow<List<SonosGroup>> = _groups.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _target = MutableStateFlow<SonosGroup?>(null)
    /** The group playing hummm's queue, or null for none. */
    val target: StateFlow<SonosGroup?> = _target.asStateFlow()

    /** Set when playback should come back to the phone paused rather than carry on. */
    @Volatile internal var pauseOnReturn = false

    fun connect(group: SonosGroup) { _target.value = group }

    /** Stops playing on Sonos; [pause] leaves the music paused on the phone instead of carrying on. */
    fun disconnect(pause: Boolean = false) {
        pauseOnReturn = pause
        _target.value = null
    }

    /** Looks for speakers on the Wi-Fi (plus any speaker added by address) and lists their groups. */
    suspend fun scan(context: Context) {
        if (_scanning.value) return
        _scanning.value = true
        try {
            val app = HummmApp.instance
            val manual = app.settings.sonosHost.value
            val found = withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                val lock = context.getSystemService(WifiManager::class.java)?.createMulticastLock("hummm:sonos")
                runCatching { lock?.acquire() }
                val hosts = try {
                    runCatching { SonosClient.discover() }.getOrDefault(emptyList())
                } finally {
                    runCatching { if (lock?.isHeld == true) lock.release() }
                }
                groupsFrom(hosts + listOfNotNull(manual?.let { it to 1400 }))
            }
            // A search that misses (multicast is flaky on busy networks) keeps the rooms already listed.
            if (found.isNotEmpty()) _groups.value = found
        } finally {
            _scanning.value = false
        }
    }

    /** Shows [groups] without searching; for tests. */
    internal fun setGroupsForTest(groups: List<SonosGroup>) { _groups.value = groups }

    /** Adds a speaker by IP address, for networks that hide speakers from searches. False if it didn't answer. */
    suspend fun addByAddress(host: String): Boolean {
        val found = withContext(Dispatchers.IO) { groupsFrom(listOf(host.trim() to 1400)) }
        if (found.isEmpty()) return false
        HummmApp.instance.settings.setSonosHost(host.trim())
        _groups.value = (_groups.value + found).distinctBy { it.id }.sortedBy { it.name.lowercase() }
        return true
    }

    /** Any one speaker knows its whole household's groups; ask a few in case one is busy or another household. */
    private fun groupsFrom(hosts: List<Pair<String, Int>>): List<SonosGroup> {
        val http = HummmApp.instance.http
        return hosts.distinct().take(6)
            .flatMap { (h, p) -> runCatching { SonosClient(http, h, p).groups() }.getOrDefault(emptyList()) }
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase() }
    }
}
