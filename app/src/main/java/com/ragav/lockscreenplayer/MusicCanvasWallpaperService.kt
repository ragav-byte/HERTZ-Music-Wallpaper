package com.ragav.lockscreenplayer

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.ragav.lockscreenplayer.data.PlaybackRepository
import com.ragav.lockscreenplayer.data.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicCanvasWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = MusicCanvasEngine()

    private enum class WallpaperSurfaceMode {
        LOCK,
        HOME
    }

    inner class MusicCanvasEngine : Engine() {
        private val keyguardManager by lazy {
            getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        }
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var latestState: PlaybackUiState = PlaybackRepository.uiState.value
        private var isVisibleOnScreen = false
        private var surfaceMode = WallpaperSurfaceMode.HOME
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var animationPhase = 0f
        private var animationJob: Job? = null
        private var surfaceStateReceiverRegistered = false
        private val surfaceStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        surfaceMode = WallpaperSurfaceMode.LOCK
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        surfaceMode = WallpaperSurfaceMode.LOCK
                        PlaybackRepository.refreshCurrentPlayback()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        surfaceMode = WallpaperSurfaceMode.HOME
                    }
                }
                restartRendering()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceMode = if (keyguardManager.isKeyguardLocked) {
                WallpaperSurfaceMode.LOCK
            } else {
                WallpaperSurfaceMode.HOME
            }
            registerSurfaceStateReceiver()

            engineScope.launch {
                PlaybackRepository.uiState.collectLatest { state ->
                    latestState = state
                    restartRendering()
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isVisibleOnScreen = visible
            if (visible) {
                surfaceMode = if (keyguardManager.isKeyguardLocked) {
                    WallpaperSurfaceMode.LOCK
                } else {
                    WallpaperSurfaceMode.HOME
                }
                PlaybackRepository.refreshCurrentPlayback()
            }
            restartRendering()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            restartRendering()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            isVisibleOnScreen = false
            animationJob?.cancel()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            unregisterSurfaceStateReceiver()
            engineScope.cancel()
            super.onDestroy()
        }

        private fun restartRendering() {
            animationJob?.cancel()
            if (!isVisibleOnScreen || surfaceWidth <= 0 || surfaceHeight <= 0) return

            if (latestState.isPlaying) {
                animationJob = engineScope.launch {
                    while (isVisibleOnScreen) {
                        PlaybackRepository.refreshCurrentPlayback()
                        latestState = PlaybackRepository.uiState.value
                        renderFrame(animationPhase)
                        if (!latestState.isPlaying) break
                        delay(500L)
                    }
                }
            } else {
                animationJob = engineScope.launch {
                    PlaybackRepository.refreshCurrentPlayback()
                    latestState = PlaybackRepository.uiState.value
                    renderFrame(animationPhase)
                }
            }
        }

        private fun shouldDrawCardsForCurrentSurface(): Boolean {
            if (!PlaybackRepository.shouldShowCard(latestState)) return false
            return if (surfaceMode == WallpaperSurfaceMode.LOCK) {
                latestState.showCardOnLockScreen
            } else {
                latestState.showCardOnHomeScreen
            }
        }

        private suspend fun renderFrame(@Suppress("UNUSED_PARAMETER") phase: Float) {
            val wallpaperBitmap = runCatching {
                withContext(Dispatchers.Default) {
                    LiveWallpaperRenderer.render(
                        context = this@MusicCanvasWallpaperService,
                        state = latestState,
                        width = surfaceWidth,
                        height = surfaceHeight,
                        drawCards = shouldDrawCardsForCurrentSurface()
                    )
                }
            }.getOrElse {
                withContext(Dispatchers.Default) {
                    LiveWallpaperRenderer.render(
                        context = this@MusicCanvasWallpaperService,
                        state = latestState.copy(artworkBitmap = null),
                        width = surfaceWidth,
                        height = surfaceHeight,
                        drawCards = false
                    )
                }
            }

            val holder = surfaceHolder ?: return
            val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                canvas.drawBitmap(wallpaperBitmap, 0f, 0f, null)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
        private fun registerSurfaceStateReceiver() {
            if (surfaceStateReceiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(surfaceStateReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(surfaceStateReceiver, filter)
            }
            surfaceStateReceiverRegistered = true
        }

        private fun unregisterSurfaceStateReceiver() {
            if (!surfaceStateReceiverRegistered) return
            runCatching { unregisterReceiver(surfaceStateReceiver) }
            surfaceStateReceiverRegistered = false
        }
    }
}
