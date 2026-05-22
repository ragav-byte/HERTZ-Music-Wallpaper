package com.ragav.lockscreenplayer

import android.app.KeyguardManager
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.ragav.lockscreenplayer.data.PlaybackRepository
import com.ragav.lockscreenplayer.data.PlaybackUiState
import android.os.SystemClock
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

    inner class MusicCanvasEngine : Engine() {
        private val keyguardManager by lazy {
            getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        }
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var latestState: PlaybackUiState = PlaybackRepository.uiState.value
        private var isVisibleOnScreen = false
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var animationPhase = 0f
        private var animationJob: Job? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

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
            engineScope.cancel()
            super.onDestroy()
        }

        private fun restartRendering() {
            animationJob?.cancel()
            if (!isVisibleOnScreen || surfaceWidth <= 0 || surfaceHeight <= 0) return
            val effectiveFluidity = maxOf(latestState.fluidity, 0.62f)
            val shouldShowCard = shouldDrawCardsForCurrentSurface()

            if (latestState.isPlaying) {
                animationJob = engineScope.launch {
                    while (isVisibleOnScreen) {
                        renderFrame(animationPhase, shouldDrawCardsForCurrentSurface())
                        animationPhase += 0.11f + effectiveFluidity * 0.11f
                        if (!latestState.isPlaying) break
                        delay(frameDelayMs(effectiveFluidity))
                    }
                }
            } else {
                animationJob = engineScope.launch {
                    renderFrame(animationPhase, shouldShowCard)
                }
            }
        }

        private fun shouldDrawCardsForCurrentSurface(): Boolean {
            if (!PlaybackRepository.shouldShowCard(latestState)) return false
            return if (keyguardManager.isKeyguardLocked) {
                latestState.showCardOnLockScreen
            } else {
                latestState.showCardOnHomeScreen
            }
        }

        private suspend fun renderFrame(phase: Float, drawCards: Boolean) {
            val wallpaperBitmap = withContext(Dispatchers.Default) {
                LiveWallpaperRenderer.render(
                    context = this@MusicCanvasWallpaperService,
                    state = latestState,
                    width = surfaceWidth,
                    height = surfaceHeight,
                    phase = phase,
                    drawCards = drawCards
                )
            }

            val holder = surfaceHolder ?: return
            val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                canvas.drawBitmap(wallpaperBitmap, 0f, 0f, null)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun frameDelayMs(fluidity: Float): Long {
            return (110L - (fluidity * 55f).toLong()).coerceIn(42L, 110L)
        }
    }
}
