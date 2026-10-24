package com.okusezu.hummm.playback

import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.framework.CastSession
import com.okusezu.hummm.HummmApp
import kotlin.math.roundToInt

/*
 * Chromecast volume. Media3 1.5's CastPlayer reports no device volume, so the
 * phone's volume keys did nothing while casting. CastVolumePlayer adds it: the
 * session then presents itself as remote playback with a 0..STEPS volume and
 * Android routes the hardware keys (and its volume slider) to the Chromecast.
 */

/** Direct access to the current Cast session's volume; main thread only. */
object CastVolume {
    const val STEPS = 20 // 5% per key press

    private val session: CastSession? get() = HummmApp.instance.castContext?.sessionManager?.currentCastSession

    val level: Int get() = ((session?.volume ?: 0.0) * STEPS).roundToInt().coerceIn(0, STEPS)
    val muted: Boolean get() = session?.isMute == true

    fun set(level: Int) {
        val s = session ?: return
        runCatching {
            if (s.isMute && level > 0) s.isMute = false
            s.volume = level.coerceIn(0, STEPS).toDouble() / STEPS
        }
    }

    fun step(delta: Int) = set(level + delta)

    fun setMuted(muted: Boolean) { runCatching { session?.isMute = muted } }
}

/** CastPlayer plus device volume, kept in step with the Chromecast's own volume changes. */
@OptIn(UnstableApi::class)
class CastVolumePlayer(cast: Player) : ForwardingPlayer(cast) {
    private val listeners = mutableListOf<Player.Listener>()
    private var attached: CastSession? = null
    private val castListener = object : Cast.Listener() {
        override fun onVolumeChanged() = publish()
    }

    /** Follows the current Cast session's volume; call when a session starts or ends. */
    fun attach(session: CastSession?) {
        if (session === attached) return
        attached?.removeCastListener(castListener)
        attached = session
        session?.addCastListener(castListener)
        publish()
    }

    private fun publish() {
        CastStatus.setVolume(if (attached != null) CastVolume.level * 100 / CastVolume.STEPS else null)
        val volume = deviceVolume
        val muted = isDeviceMuted
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_DEVICE_VOLUME_CHANGED).build())
        listeners.toList().forEach {
            it.onDeviceVolumeChanged(volume, muted)
            it.onEvents(this, events)
        }
    }

    override fun addListener(listener: Player.Listener) {
        listeners += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
        super.removeListener(listener)
    }

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*VOLUME_COMMANDS).build()

    override fun isCommandAvailable(command: Int): Boolean = command in VOLUME_COMMANDS || super.isCommandAvailable(command)

    override fun getDeviceInfo(): DeviceInfo = INFO

    override fun getDeviceVolume(): Int = CastVolume.level

    override fun isDeviceMuted(): Boolean = CastVolume.muted

    override fun setDeviceVolume(volume: Int, flags: Int) = CastVolume.set(volume)

    override fun increaseDeviceVolume(flags: Int) = CastVolume.step(1)

    override fun decreaseDeviceVolume(flags: Int) = CastVolume.step(-1)

    override fun setDeviceMuted(muted: Boolean, flags: Int) = CastVolume.setMuted(muted)

    @Deprecated("Deprecated in Java")
    override fun setDeviceVolume(volume: Int) = CastVolume.set(volume)

    @Deprecated("Deprecated in Java")
    override fun increaseDeviceVolume() = CastVolume.step(1)

    @Deprecated("Deprecated in Java")
    override fun decreaseDeviceVolume() = CastVolume.step(-1)

    @Deprecated("Deprecated in Java")
    override fun setDeviceMuted(muted: Boolean) = CastVolume.setMuted(muted)

    private companion object {
        val INFO = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(CastVolume.STEPS).build()
        @Suppress("DEPRECATION")
        val VOLUME_COMMANDS = intArrayOf(
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )
    }
}
