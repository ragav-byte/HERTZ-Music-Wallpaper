package com.ragav.lockscreenplayer

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import com.ragav.lockscreenplayer.data.PlaybackRefreshMode
import com.ragav.lockscreenplayer.data.PlaybackRepository
import com.ragav.lockscreenplayer.data.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MusicCanvasWallpaperService : WallpaperService() {
    private companion object {
        private const val PROGRESS_REDRAW_INTERVAL_MS = 250L
        private const val MARQUEE_REDRAW_INTERVAL_MS = 33L
        private const val MARQUEE_START_DELAY_MS = 2_000L
    }

    override fun onCreateEngine(): Engine = MusicCanvasEngine()

    private enum class WallpaperSurfaceMode {
        LOCK,
        HOME
    }

    private data class RenderSnapshotKey(
        val width: Int,
        val height: Int,
        val surfaceMode: WallpaperSurfaceMode,
        val drawCards: Boolean,
        val title: String,
        val artist: String,
        val album: String,
        val sourcePackage: String,
        val isExplicit: Boolean,
        val isPlaying: Boolean,
        val durationMs: Long,
        val pausedPositionMs: Long,
        val artworkSignature: String,
        val trackSignature: String,
        val layoutFingerprint: Int
    )

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
        private var animationJob: Job? = null
        private var lastImmediateRenderKey: RenderSnapshotKey? = null
        private var screenOn = true
        private var surfaceStateReceiverRegistered = false
        private val surfaceStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false
                        surfaceMode = WallpaperSurfaceMode.LOCK
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        surfaceMode = WallpaperSurfaceMode.LOCK
                        PlaybackRepository.refreshCurrentPlayback(PlaybackRefreshMode.WAKE_CACHE_FIRST)
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        surfaceMode = WallpaperSurfaceMode.HOME
                        PlaybackRepository.refreshCurrentPlayback(PlaybackRefreshMode.WAKE_CACHE_FIRST)
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
            if (!visible) {
                lastImmediateRenderKey = null
            }
            if (visible) {
                surfaceMode = if (keyguardManager.isKeyguardLocked) {
                    WallpaperSurfaceMode.LOCK
                } else {
                    WallpaperSurfaceMode.HOME
                }
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
            lastImmediateRenderKey = null
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

            animationJob = engineScope.launch {
                var drawCards = shouldDrawCardsForCurrentSurface()
                val marqueeStartedAtMs = SystemClock.elapsedRealtime()
                var lastMarqueeRenderAtMs = 0L
                var shouldMarquee = shouldTickMarquee(drawCards)
                val shouldProgress = shouldTickProgress(drawCards)
                val initialRenderKey = renderSnapshotKey(drawCards)
                if (shouldMarquee || shouldProgress || initialRenderKey != lastImmediateRenderKey) {
                    renderFrame(drawCards, marqueeElapsedMs = if (shouldMarquee) 1L else 0L)
                    if (!shouldMarquee && !shouldProgress) {
                        lastImmediateRenderKey = initialRenderKey
                    }
                }

                while (isActive && isVisibleOnScreen && screenOn) {
                    shouldMarquee = shouldTickMarquee(drawCards)
                    val shouldProgressTick = shouldTickProgress(drawCards)
                    if (!shouldMarquee && !shouldProgressTick) break

                    val elapsedSinceMarqueeStart = SystemClock.elapsedRealtime() - marqueeStartedAtMs
                    if (shouldMarquee && elapsedSinceMarqueeStart >= MARQUEE_START_DELAY_MS) {
                        val frameTimeMs = awaitNextFrameMs()
                        if (lastMarqueeRenderAtMs > 0L &&
                            frameTimeMs - lastMarqueeRenderAtMs < MARQUEE_REDRAW_INTERVAL_MS
                        ) {
                            continue
                        }
                        lastMarqueeRenderAtMs = frameTimeMs
                    } else {
                        val delayMs = if (shouldMarquee) {
                            minOf(PROGRESS_REDRAW_INTERVAL_MS, MARQUEE_START_DELAY_MS - elapsedSinceMarqueeStart)
                                .coerceAtLeast(16L)
                        } else {
                            PROGRESS_REDRAW_INTERVAL_MS
                        }
                        delay(delayMs)
                    }

                    drawCards = shouldDrawCardsForCurrentSurface()
                    val marqueeElapsedMs = if (shouldMarquee && drawCards && latestState.isPlaying) {
                        SystemClock.elapsedRealtime() - marqueeStartedAtMs
                    } else {
                        0L
                    }
                    renderFrame(drawCards, marqueeElapsedMs)
                }
                schedulePausedCardHideIfNeeded(drawCards)
            }
        }

        private suspend fun schedulePausedCardHideIfNeeded(drawCards: Boolean) {
            if (!drawCards || latestState.isPlaying) return
            val remainingMs = PlaybackRepository.remainingCardPauseHoldMs(latestState)
            if (remainingMs <= 0L) return
            delay(remainingMs + 32L)
            if (isVisibleOnScreen && !PlaybackRepository.shouldShowCard(latestState)) {
                renderFrame(drawCards = false)
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

        private fun shouldTickProgress(drawCards: Boolean): Boolean {
            return screenOn && drawCards && latestState.isPlaying && latestState.durationMs > 0L
        }

        private fun shouldTickMarquee(drawCards: Boolean): Boolean {
            return screenOn &&
                surfaceMode == WallpaperSurfaceMode.LOCK &&
                drawCards &&
                latestState.lockscreenMarqueeEnabled &&
                latestState.isPlaying &&
                LiveWallpaperRenderer.hasLockscreenTextOverflow(
                    context = this@MusicCanvasWallpaperService,
                    state = latestState,
                    width = surfaceWidth,
                    height = surfaceHeight,
                    drawCards = drawCards
                )
        }

        private fun renderSnapshotKey(drawCards: Boolean): RenderSnapshotKey {
            val state = latestState
            val layoutFingerprint = listOf(
                state.cardOffsetX,
                state.cardOffsetY,
                state.cardScale,
                state.playerCardWidthScale,
                state.playerCardOffsetY,
                state.cardCornerRadius,
                state.playerCardFrost,
                state.showCardOnLockScreen,
                state.showCardOnHomeScreen,
                state.cardPauseHoldMs,
                state.textOffsetX,
                state.textOffsetY,
                state.titleTextScale,
                state.artistTextScale,
                state.blurAmount,
                state.fluidScale,
                state.fluidity,
                state.gradientBrightness,
                state.gradientAnchorPreset,
                state.gradientAnchor1X,
                state.gradientAnchor1Y,
                state.gradientAnchor2X,
                state.gradientAnchor2Y,
                state.gradientAnchor3X,
                state.gradientAnchor3Y,
                state.preserveArtworkOnReboot,
                state.lockscreenMarqueeEnabled,
                state.cardsDisabledForBattery,
                state.textAlignment
            ).hashCode()
            return RenderSnapshotKey(
                width = surfaceWidth,
                height = surfaceHeight,
                surfaceMode = surfaceMode,
                drawCards = drawCards,
                title = state.title,
                artist = state.artist,
                album = state.album,
                sourcePackage = state.sourcePackage,
                isExplicit = state.isExplicit,
                isPlaying = state.isPlaying,
                durationMs = state.durationMs,
                pausedPositionMs = if (state.isPlaying) -1L else state.positionMs,
                artworkSignature = state.artworkSignature,
                trackSignature = state.trackSignature,
                layoutFingerprint = layoutFingerprint
            )
        }

        private suspend fun renderFrame(drawCards: Boolean, marqueeElapsedMs: Long = 0L) {
            val wallpaperBitmap = runCatching {
                withContext(Dispatchers.Default) {
                    LiveWallpaperRenderer.render(
                        context = this@MusicCanvasWallpaperService,
                        state = latestState,
                        width = surfaceWidth,
                        height = surfaceHeight,
                        marqueeElapsedMs = marqueeElapsedMs,
                        drawCards = drawCards
                    )
                }
            }.getOrElse {
                withContext(Dispatchers.Default) {
                    LiveWallpaperRenderer.render(
                        context = this@MusicCanvasWallpaperService,
                        state = latestState.copy(artworkBitmap = null),
                        width = surfaceWidth,
                        height = surfaceHeight,
                        marqueeElapsedMs = 0L,
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

        private suspend fun awaitNextFrameMs(): Long {
            return suspendCancellableCoroutine { continuation ->
                val choreographer = Choreographer.getInstance()
                val callback = Choreographer.FrameCallback { frameTimeNanos ->
                    if (continuation.isActive) {
                        continuation.resume(frameTimeNanos / 1_000_000L)
                    }
                }
                choreographer.postFrameCallback(callback)
                continuation.invokeOnCancellation {
                    choreographer.removeFrameCallback(callback)
                }
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
