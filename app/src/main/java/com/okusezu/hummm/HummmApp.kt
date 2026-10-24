package com.okusezu.hummm

import android.app.Application
import android.util.Log
import com.google.android.gms.cast.framework.CastContext
import com.okusezu.hummm.data.InnerTube
import com.okusezu.hummm.data.Music
import com.okusezu.hummm.data.Settings
import com.okusezu.hummm.data.Streams
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Process-wide singletons shared by the UI and the playback service. */
class HummmApp : Application() {
    lateinit var settings: Settings
    lateinit var http: OkHttpClient
    lateinit var music: Music
    val streams: Streams by lazy { Streams(http) }

    /** Null on phones without Google Play services, where casting isn't available. Main thread only. */
    val castContext: CastContext? by lazy {
        runCatching { CastContext.getSharedInstance(this) }
            .onFailure { Log.i("hummm", "Cast unavailable: ${it.message}") }
            .getOrNull()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        music = Music(InnerTube(http, { settings.cookie.value }, language = settings.language))
    }

    companion object {
        lateinit var instance: HummmApp
            private set
    }
}
