package com.ragav.lockscreenplayer

import android.app.Application
import com.ragav.lockscreenplayer.data.PlaybackRepository

class MusicCanvasApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlaybackRepository.initialize(this)
    }
}
