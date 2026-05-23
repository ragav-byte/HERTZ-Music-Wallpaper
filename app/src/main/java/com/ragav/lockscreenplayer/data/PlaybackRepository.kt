package com.ragav.lockscreenplayer.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

data class PlaybackUiState(
    val title: String = "Start playing your music",
    val artist: String = "The current song will appear here",
    val album: String = "",
    val sourceApp: String = "No player detected",
    val sourcePackage: String = "",
    val playbackDeviceLabel: String = "This device",
    val isExplicit: Boolean = false,
    val artworkBitmap: Bitmap? = null,
    val artworkSignature: String = "",
    val hasSourceSession: Boolean = false,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 0f,
    val pausedAtMs: Long = 0L,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val positionCapturedAtMs: Long = 0L,
    val cardOffsetX: Float = 0f,
    val cardOffsetY: Float = 1.33f,
    val cardScale: Float = 0.88f,
    val playerCardWidthScale: Float = 0.86f,
    val playerCardOffsetY: Float = -0.09f,
    val cardCornerRadius: Float = 0.02f,
    val playerCardFrost: Float = 0.25f,
    val showCardOnLockScreen: Boolean = true,
    val showCardOnHomeScreen: Boolean = false,
    val cardPauseHoldMs: Long = 0L,
    val textOffsetX: Float = 0f,
    val textOffsetY: Float = 0f,
    val titleTextScale: Float = 1.0f,
    val artistTextScale: Float = 1.08f,
    val blurAmount: Float = 0.72f,
    val fluidScale: Float = 0.82f,
    val fluidity: Float = 0.62f,
    val gradientBrightness: Float = 1.0f,
    val preserveArtworkOnReboot: Boolean = false,
    val batteryPercent: Int = 100,
    val cardsDisabledForBattery: Boolean = false,
    val textAlignment: TextAlignmentOption = TextAlignmentOption.CENTER,
    val trackSignature: String = "",
    val marqueeStartedAtMs: Long = 0L
)

enum class TextAlignmentOption {
    LEFT,
    CENTER,
    RIGHT
}

object PlaybackRepository {
    val CARD_PAUSE_HOLD_OPTIONS_MS = listOf(0L, 5_000L, 10_000L, 20_000L, 30_000L, 60_000L, 300_000L, 600_000L)
    private const val CARD_STEP = 0.12f
    private const val TEXT_STEP = 0.08f
    private const val MIN_CARD_SCALE = 0.34f
    private const val MAX_CARD_SCALE = 0.88f
    private const val MIN_PLAYER_CARD_WIDTH = 0.56f
    private const val MAX_PLAYER_CARD_WIDTH = 0.96f
    private const val MIN_PLAYER_CARD_OFFSET_Y = -1.0f
    private const val MAX_PLAYER_CARD_OFFSET_Y = 2.4f
    private const val MIN_CARD_RADIUS = 0f
    private const val MAX_CARD_RADIUS = 0.30f
    private const val MIN_PLAYER_CARD_FROST = 0.15f
    private const val MAX_PLAYER_CARD_FROST = 1f
    private const val CARD_X_MIN = -1.5f
    private const val CARD_X_MAX = 1.5f
    private const val CARD_Y_MIN = -0.6f
    private const val CARD_Y_MAX = 3.2f
    private const val TEXT_X_MIN = -1f
    private const val TEXT_X_MAX = 1f
    private const val TEXT_Y_MIN = -0.6f
    private const val TEXT_Y_MAX = 0.9f
    private const val MIN_TEXT_SCALE = 0.75f
    private const val MAX_TEXT_SCALE = 1.35f
    private const val PREFS_NAME = "music_canvas_prefs"
    private const val KEY_CARD_X = "card_x"
    private const val KEY_CARD_Y = "card_y"
    private const val KEY_CARD_SCALE = "card_scale"
    private const val KEY_PLAYER_CARD_WIDTH = "player_card_width"
    private const val KEY_PLAYER_CARD_OFFSET_Y = "player_card_offset_y"
    private const val KEY_CARD_RADIUS = "card_radius"
    private const val KEY_PLAYER_CARD_FROST = "player_card_frost"
    private const val KEY_SHOW_CARD_ON_LOCK_SCREEN = "show_card_on_lock_screen"
    private const val KEY_SHOW_CARD_ON_HOME_SCREEN = "show_card_on_home_screen"
    private const val KEY_CARD_PAUSE_HOLD_MS = "card_pause_hold_ms"
    private const val KEY_TEXT_X = "text_x"
    private const val KEY_TEXT_Y = "text_y"
    private const val KEY_TITLE_TEXT_SCALE = "title_text_scale"
    private const val KEY_ARTIST_TEXT_SCALE = "artist_text_scale"
    private const val KEY_BLUR_AMOUNT = "blur_amount"
    private const val KEY_FLUID_SCALE = "fluid_scale"
    private const val KEY_FLUIDITY = "fluidity"
    private const val KEY_GRADIENT_BRIGHTNESS = "gradient_brightness"
    private const val KEY_PRESERVE_ARTWORK_ON_REBOOT = "preserve_artwork_on_reboot"
    private const val KEY_TEXT_ALIGNMENT = "text_alignment"
    private const val DURATION_CACHE_PREFIX = "duration_cache_"
    private const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
    private const val NEW_TRACK_POSITION_GRACE_MS = 5_000L
    private const val SAME_TRACK_RESTART_WINDOW_MS = 2_500L
    private const val SAME_TRACK_RESTART_MIN_POSITION_MS = 6_000L
    private const val BACKWARD_SEEK_THRESHOLD_MS = 5_000L
    private const val TARGET_ARTWORK_SIZE = 512

    private val mutableUiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = mutableUiState.asStateFlow()

    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentController: MediaController? = null
    private var appContext: Context? = null
    private var imageLoader: ImageLoader? = null
    private var batteryReceiverRegistered = false
    @Volatile
    private var activeArtworkRequestKey: String? = null
    private val badArtworkRequestKeys = linkedSetOf<String>()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryState(intent)
        }
    }

    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        imageLoader = ImageLoader.Builder(context.applicationContext)
            .crossfade(false)
            .build()
        val prefs = requirePrefs()
        val preserveArtworkOnReboot = prefs.getBoolean(KEY_PRESERVE_ARTWORK_ON_REBOOT, false)
        ArtworkCache.initialize(context.applicationContext, preserveArtworkOnReboot)
        registerBatteryReceiver(context.applicationContext)
        mutableUiState.value = mutableUiState.value.copy(
            cardOffsetX = prefs.getFloat(KEY_CARD_X, 0f).coerceIn(CARD_X_MIN, CARD_X_MAX),
            cardOffsetY = prefs.getFloat(KEY_CARD_Y, 1.33f).coerceIn(CARD_Y_MIN, CARD_Y_MAX),
            cardScale = prefs.getFloat(KEY_CARD_SCALE, 0.88f).coerceIn(MIN_CARD_SCALE, MAX_CARD_SCALE),
            playerCardWidthScale = prefs.getFloat(KEY_PLAYER_CARD_WIDTH, 0.86f)
                .coerceIn(MIN_PLAYER_CARD_WIDTH, MAX_PLAYER_CARD_WIDTH),
            playerCardOffsetY = prefs.getFloat(KEY_PLAYER_CARD_OFFSET_Y, -0.09f)
                .coerceIn(MIN_PLAYER_CARD_OFFSET_Y, MAX_PLAYER_CARD_OFFSET_Y),
            cardCornerRadius = prefs.getFloat(KEY_CARD_RADIUS, 0.02f).coerceIn(MIN_CARD_RADIUS, MAX_CARD_RADIUS),
            playerCardFrost = prefs.getFloat(KEY_PLAYER_CARD_FROST, 0.25f)
                .coerceIn(MIN_PLAYER_CARD_FROST, MAX_PLAYER_CARD_FROST),
            showCardOnLockScreen = prefs.getBoolean(KEY_SHOW_CARD_ON_LOCK_SCREEN, true),
            showCardOnHomeScreen = prefs.getBoolean(KEY_SHOW_CARD_ON_HOME_SCREEN, false),
            cardPauseHoldMs = normalizedPauseHoldMs(prefs.getLong(KEY_CARD_PAUSE_HOLD_MS, 0L)),
            textOffsetX = prefs.getFloat(KEY_TEXT_X, 0f).coerceIn(TEXT_X_MIN, TEXT_X_MAX),
            textOffsetY = prefs.getFloat(KEY_TEXT_Y, 0f).coerceIn(TEXT_Y_MIN, TEXT_Y_MAX),
            titleTextScale = prefs.getFloat(KEY_TITLE_TEXT_SCALE, 1.0f).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
            artistTextScale = prefs.getFloat(KEY_ARTIST_TEXT_SCALE, 1.08f).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
            blurAmount = prefs.getFloat(KEY_BLUR_AMOUNT, 0.72f).coerceIn(0f, 1f),
            fluidScale = prefs.getFloat(KEY_FLUID_SCALE, 0.82f).coerceIn(0f, 1f),
            fluidity = prefs.getFloat(KEY_FLUIDITY, 0.62f).coerceIn(0f, 1f),
            gradientBrightness = prefs.getFloat(KEY_GRADIENT_BRIGHTNESS, 1.0f).coerceIn(0.65f, 1.65f),
            preserveArtworkOnReboot = preserveArtworkOnReboot,
            textAlignment = prefs.getString(KEY_TEXT_ALIGNMENT, TextAlignmentOption.CENTER.name)
                ?.let { runCatching { TextAlignmentOption.valueOf(it) }.getOrNull() }
                ?: TextAlignmentOption.CENTER
        )
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            syncFromController(currentController)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            syncFromController(currentController)
        }

        override fun onSessionDestroyed() {
            attachController(null)
        }
    }

    fun attachController(controller: MediaController?) {
        if (currentController === controller) {
            syncFromController(controller)
            return
        }

        currentController?.unregisterCallback(controllerCallback)
        currentController = controller
        controller?.registerCallback(controllerCallback)
        syncFromController(controller)
    }

    fun refreshCurrentPlayback() {
        syncFromController(currentController)
    }

    fun refreshBatteryState() {
        val context = appContext ?: return
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        updateBatteryState(batteryIntent)
    }

    fun moveCard(dx: Int, dy: Int) {
        setCardOffset(
            x = uiState.value.cardOffsetX + dx * CARD_STEP,
            y = uiState.value.cardOffsetY + dy * CARD_STEP
        )
    }

    fun moveText(dx: Int, dy: Int) {
        setTextOffset(
            x = uiState.value.textOffsetX + dx * TEXT_STEP,
            y = uiState.value.textOffsetY + dy * TEXT_STEP
        )
    }

    fun setCardOffset(x: Float = uiState.value.cardOffsetX, y: Float = uiState.value.cardOffsetY) {
        mutableUiState.update { state ->
            state.copy(
                cardOffsetX = x.coerceIn(CARD_X_MIN, CARD_X_MAX),
                cardOffsetY = y.coerceIn(CARD_Y_MIN, CARD_Y_MAX)
            )
        }
        persistLayout()
    }

    fun setTextOffset(x: Float = uiState.value.textOffsetX, y: Float = uiState.value.textOffsetY) {
        mutableUiState.update { state ->
            state.copy(
                textOffsetX = x.coerceIn(TEXT_X_MIN, TEXT_X_MAX),
                textOffsetY = y.coerceIn(TEXT_Y_MIN, TEXT_Y_MAX)
            )
        }
        persistLayout()
    }

    fun setCardScale(scale: Float) {
        mutableUiState.update { state ->
            state.copy(cardScale = scale.coerceIn(MIN_CARD_SCALE, MAX_CARD_SCALE))
        }
        persistLayout()
    }

    fun setPlayerCardWidthScale(scale: Float) {
        mutableUiState.update { state ->
            state.copy(playerCardWidthScale = scale.coerceIn(MIN_PLAYER_CARD_WIDTH, MAX_PLAYER_CARD_WIDTH))
        }
        persistLayout()
    }

    fun setPlayerCardOffsetY(offsetY: Float) {
        mutableUiState.update { state ->
            state.copy(playerCardOffsetY = offsetY.coerceIn(MIN_PLAYER_CARD_OFFSET_Y, MAX_PLAYER_CARD_OFFSET_Y))
        }
        persistLayout()
    }

    fun setCardCornerRadius(radius: Float) {
        mutableUiState.update { state ->
            state.copy(cardCornerRadius = radius.coerceIn(MIN_CARD_RADIUS, MAX_CARD_RADIUS))
        }
        persistLayout()
    }

    fun setPlayerCardFrost(frost: Float) {
        mutableUiState.update { state ->
            state.copy(playerCardFrost = frost.coerceIn(MIN_PLAYER_CARD_FROST, MAX_PLAYER_CARD_FROST))
        }
        persistLayout()
    }

    fun setShowCardOnLockScreen(enabled: Boolean) {
        mutableUiState.update { state ->
            state.copy(showCardOnLockScreen = enabled)
        }
        persistLayout()
    }

    fun setShowCardOnHomeScreen(enabled: Boolean) {
        mutableUiState.update { state ->
            state.copy(showCardOnHomeScreen = enabled)
        }
        persistLayout()
    }

    fun setCardPauseHoldMs(durationMs: Long) {
        mutableUiState.update { state ->
            state.copy(cardPauseHoldMs = normalizedPauseHoldMs(durationMs))
        }
        persistLayout()
    }

    fun setTitleTextScale(scale: Float) {
        mutableUiState.update { state ->
            state.copy(titleTextScale = scale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE))
        }
        persistLayout()
    }

    fun setArtistTextScale(scale: Float) {
        mutableUiState.update { state ->
            state.copy(artistTextScale = scale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE))
        }
        persistLayout()
    }

    fun setBlurAmount(blurAmount: Float) {
        mutableUiState.update { state ->
            state.copy(blurAmount = blurAmount.coerceIn(0f, 1f))
        }
        persistLayout()
    }

    fun setFluidScale(fluidScale: Float) {
        mutableUiState.update { state ->
            state.copy(fluidScale = fluidScale.coerceIn(0f, 1f))
        }
        persistLayout()
    }

    fun setFluidity(fluidity: Float) {
        mutableUiState.update { state ->
            state.copy(fluidity = fluidity.coerceIn(0f, 1f))
        }
        persistLayout()
    }

    fun setGradientBrightness(brightness: Float) {
        mutableUiState.update { state ->
            state.copy(gradientBrightness = brightness.coerceIn(0.65f, 1.65f))
        }
        persistLayout()
    }

    fun setPreserveArtworkOnReboot(enabled: Boolean) {
        mutableUiState.update { state ->
            state.copy(preserveArtworkOnReboot = enabled)
        }
        ArtworkCache.setPreserveAcrossReboot(enabled)
        persistLayout()
    }

    fun setTextAlignment(alignment: TextAlignmentOption) {
        mutableUiState.update { state ->
            state.copy(textAlignment = alignment)
        }
        persistLayout()
    }

    fun resetLayout() {
        mutableUiState.update { state ->
            state.copy(
                cardOffsetX = 0f,
                cardOffsetY = 1.33f,
                cardScale = 0.88f,
                playerCardWidthScale = 0.86f,
                playerCardOffsetY = -0.09f,
                cardCornerRadius = 0.02f,
                playerCardFrost = 0.25f,
                showCardOnLockScreen = true,
                showCardOnHomeScreen = false,
                cardPauseHoldMs = 0L,
                textOffsetX = 0f,
                textOffsetY = 0f,
                titleTextScale = 1.0f,
                artistTextScale = 1.08f,
                blurAmount = 0.72f,
                fluidScale = 0.82f,
                fluidity = 0.62f,
                gradientBrightness = 1.0f,
                preserveArtworkOnReboot = false,
                textAlignment = TextAlignmentOption.CENTER
            )
        }
        ArtworkCache.setPreserveAcrossReboot(false)
        persistLayout()
    }

    fun primeFromNotification(
        packageName: String,
        title: String?,
        artist: String?,
        artwork: Bitmap?,
        isExplicit: Boolean? = null
    ) {
        val cleanTitle = title?.trim().orEmpty()
        val cleanArtist = artist?.trim().orEmpty()
        if (cleanTitle.isBlank() && cleanArtist.isBlank() && artwork == null) return

        mutableUiState.update { state ->
            val shouldAccept = state.sourcePackage.isBlank() ||
                state.sourcePackage == packageName ||
                packageName == APPLE_MUSIC_PACKAGE
            val now = SystemClock.elapsedRealtime()

            if (!shouldAccept) {
                state
            } else {
                val updatedTitle = cleanTitle.ifBlank { state.title }
                val updatedArtist = cleanArtist.ifBlank { state.artist }
                val resolvedPackage = if (state.sourcePackage.isBlank()) packageName else state.sourcePackage
                val cacheKeys = artworkCacheKeys(
                    packageName = resolvedPackage,
                    title = updatedTitle,
                    artist = updatedArtist,
                    album = state.album
                )
                val updatedSignature = cacheKeys.first()
                val signatureChanged = state.trackSignature != updatedSignature
                val memoryArtwork = ArtworkCache.getMemorySync(cacheKeys)
                val cachedDuration = cachedDurationMs(cacheKeys)
                if (artwork != null) {
                    ArtworkCache.storeAsync(cacheKeys, artwork)
                }
                val explicit = isExplicit == true || inferExplicitFlag(title = updatedTitle, metadata = null, extras = null)
                if (state.hasSourceSession && state.sourcePackage == packageName) {
                    return@update if (explicit && !state.isExplicit && !signatureChanged) {
                        state.copy(isExplicit = true)
                    } else {
                        state
                    }
                }
                val resolvedArtwork = if (signatureChanged) {
                    memoryArtwork ?: artwork ?: state.artworkBitmap
                } else {
                    chooseSharperArtwork(state.artworkBitmap, memoryArtwork ?: artwork)
                }
                val resolvedArtworkSignature = if (
                    resolvedArtwork != null &&
                    (resolvedArtwork === memoryArtwork || resolvedArtwork === artwork)
                ) {
                    updatedSignature
                } else {
                    state.artworkSignature
                }
                val resolvedExplicit = explicit || (!signatureChanged && state.isExplicit)
                val resolvedDuration = if (signatureChanged) cachedDuration ?: 0L else cachedDuration ?: state.durationMs
                val sameTrackNoVisualChange = !signatureChanged &&
                    updatedTitle == state.title &&
                    updatedArtist == state.artist &&
                    resolvedExplicit == state.isExplicit &&
                    resolvedArtwork === state.artworkBitmap &&
                    resolvedDuration == state.durationMs
                if (sameTrackNoVisualChange) {
                    return@update state
                }
                state.copy(
                    title = updatedTitle,
                    artist = updatedArtist,
                    sourceApp = if (state.sourcePackage.isBlank()) readableSourceName(packageName) else state.sourceApp,
                    sourcePackage = if (state.sourcePackage.isBlank()) packageName else state.sourcePackage,
                    isExplicit = resolvedExplicit,
                    artworkBitmap = resolvedArtwork,
                    artworkSignature = resolvedArtworkSignature,
                    durationMs = resolvedDuration,
                    positionMs = if (signatureChanged) 0L else state.positionMs,
                    positionCapturedAtMs = if (signatureChanged) now else state.positionCapturedAtMs,
                    trackSignature = updatedSignature,
                    marqueeStartedAtMs = if (signatureChanged || state.marqueeStartedAtMs == 0L) {
                        now
                    } else {
                        state.marqueeStartedAtMs
                    }
                )
            }
        }
    }

    private fun syncFromController(controller: MediaController?) {
        runCatching {
            syncFromControllerSafely(controller)
        }.onFailure {
            activeArtworkRequestKey = null
            mutableUiState.update { state ->
                state.copy(
                    hasSourceSession = controller != null || state.hasSourceSession,
                    isPlaying = false,
                    playbackSpeed = 0f,
                    pausedAtMs = SystemClock.elapsedRealtime()
                )
            }
        }
    }

    private fun syncFromControllerSafely(controller: MediaController?) {
        if (controller == null) {
            mutableUiState.update { state ->
                state.copy(
                    hasSourceSession = false,
                    isPlaying = false,
                    playbackSpeed = 0f,
                    pausedAtMs = SystemClock.elapsedRealtime()
                )
            }
            return
        }

        val metadata = runCatching { controller.metadata }.getOrNull()
        val description = runCatching { metadata?.description }.getOrNull()
        val packageName = runCatching { controller.packageName }.getOrNull().orEmpty()
        val playbackState = runCatching { controller.playbackState }.getOrNull()
        val playbackStateCode = playbackState?.state
        val trackTitle = description?.title?.toString().orEmpty()
            .ifBlank { metadata.safeString(MediaMetadata.METADATA_KEY_TITLE) }
            .ifBlank { uiState.value.title.takeUnless { it == "Start playing your music" }.orEmpty() }
            .ifBlank { "Unknown title" }
        val trackArtist = description?.subtitle?.toString().orEmpty()
            .ifBlank { metadata.safeString(MediaMetadata.METADATA_KEY_ARTIST) }
            .ifBlank { metadata.safeString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) }
            .ifBlank { uiState.value.artist.takeUnless { it == "The current song will appear here" }.orEmpty() }
            .ifBlank { "Unknown artist" }
        val trackAlbum = metadata.safeString(MediaMetadata.METADATA_KEY_ALBUM)
        val artworkUri = metadata?.preferredArtworkUri()
        val metadataArtwork = metadata.safeBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.safeBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.safeBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val cacheKeys = artworkCacheKeys(
            packageName = packageName,
            title = trackTitle,
            artist = trackArtist,
            album = trackAlbum
        )
        val signature = cacheKeys.first()
        val isPlayingNow = playbackStateCode == PlaybackState.STATE_PLAYING
        val playbackSpeed = playbackState.playbackSpeedFor(isPlayingNow)
        val rawPosition = playbackState?.currentPositionMs(playbackSpeed)
        val metadataDuration = metadata.safeLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L)
        val cachedDuration = cachedDurationMs(cacheKeys)
        if (metadataDuration != null && metadataDuration > 0L) {
            rememberDuration(cacheKeys, metadataDuration)
        }
        val immediateArtwork = metadataArtwork.safeResizedForWallpaper()
        if (immediateArtwork != null) {
            ArtworkCache.storeAsync(cacheKeys, immediateArtwork)
        }
        val memoryArtwork = ArtworkCache.getMemorySync(cacheKeys)
        requestArtworkLoad(
            signature = signature,
            cacheKeys = cacheKeys,
            uri = artworkUri,
            fallbackBitmap = metadataArtwork
        )
        val explicit = inferExplicitFlag(
            title = trackTitle,
            metadata = metadata,
            extras = runCatching { description?.extras }.getOrNull()
        ) || inferExplicitFlag(
            title = trackTitle,
            metadata = metadata,
            extras = runCatching { playbackState?.extras }.getOrNull()
        )

        mutableUiState.update { state ->
            val now = SystemClock.elapsedRealtime()
            val signatureChanged = state.trackSignature != signature
            val predictedPosition = if (state.isPlaying) {
                state.positionMs + ((now - state.positionCapturedAtMs).coerceAtLeast(0L) * state.playbackSpeed).toLong()
            } else {
                state.positionMs
            }
            val resolvedPosition = when {
                signatureChanged -> rawPosition?.takeIf { it <= NEW_TRACK_POSITION_GRACE_MS } ?: 0L
                rawPosition != null && isPlayingNow && state.isPlaying && rawPosition < predictedPosition -> {
                    val backwardDelta = predictedPosition - rawPosition
                    val restartedNearBeginning = rawPosition <= SAME_TRACK_RESTART_WINDOW_MS &&
                        predictedPosition >= SAME_TRACK_RESTART_MIN_POSITION_MS
                    if (restartedNearBeginning || backwardDelta >= BACKWARD_SEEK_THRESHOLD_MS) {
                        rawPosition
                    } else {
                        predictedPosition
                    }
                }
                rawPosition != null -> rawPosition
                else -> state.positionMs
            }
            val resolvedDuration = metadataDuration ?: cachedDuration ?: if (signatureChanged) 0L else state.durationMs
            val durationLimit = resolvedDuration.takeIf { it > 0L } ?: Long.MAX_VALUE
            val artworkCandidate = memoryArtwork ?: immediateArtwork
            val earlyTrackArtworkWindow = now - state.marqueeStartedAtMs <= 3_000L
            val resolvedArtwork = if (signatureChanged) {
                artworkCandidate ?: state.artworkBitmap
            } else if (artworkCandidate != null && earlyTrackArtworkWindow && artworkCandidate !== state.artworkBitmap) {
                artworkCandidate
            } else if (artworkCandidate != null) {
                chooseSessionArtwork(
                    current = state.artworkBitmap,
                    candidate = artworkCandidate
                )
            } else {
                chooseSharperArtwork(
                    current = state.artworkBitmap,
                    candidate = artworkCandidate
                )
            }
            val resolvedArtworkSignature = if (resolvedArtwork != null && resolvedArtwork === artworkCandidate) {
                signature
            } else {
                state.artworkSignature
            }
            val resolvedExplicit = explicit || (!signatureChanged && state.isExplicit)
            val positionDrift = rawPosition?.let { abs(it - predictedPosition) } ?: 0L
            val onlyPlaybackTick = !signatureChanged &&
                isPlayingNow &&
                state.isPlaying &&
                trackTitle == state.title &&
                trackArtist == state.artist &&
                trackAlbum == state.album &&
                resolvedDuration == state.durationMs &&
                resolvedExplicit == state.isExplicit &&
                playbackSpeed == state.playbackSpeed &&
                resolvedArtwork === state.artworkBitmap &&
                positionDrift < 1_500L
            if (onlyPlaybackTick) {
                return@update state
            }
            state.copy(
                title = trackTitle,
                artist = trackArtist,
                album = trackAlbum,
                sourceApp = readableSourceName(packageName),
                sourcePackage = packageName,
                playbackDeviceLabel = playbackDeviceLabel(controller),
                isExplicit = resolvedExplicit,
                artworkBitmap = resolvedArtwork,
                artworkSignature = resolvedArtworkSignature,
                hasSourceSession = true,
                isPlaying = isPlayingNow,
                playbackSpeed = playbackSpeed,
                pausedAtMs = if (isPlayingNow) 0L else if (state.pausedAtMs == 0L) now else state.pausedAtMs,
                durationMs = resolvedDuration,
                positionMs = resolvedPosition.coerceIn(0L, durationLimit),
                positionCapturedAtMs = now,
                trackSignature = signature,
                marqueeStartedAtMs = if (signatureChanged || state.marqueeStartedAtMs == 0L) now else state.marqueeStartedAtMs
            )
        }
    }

    fun shouldShowCard(state: PlaybackUiState = uiState.value): Boolean {
        if (!state.hasSourceSession || state.cardsDisabledForBattery) return false
        if (state.isPlaying) return true
        if (state.cardPauseHoldMs <= 0L || state.pausedAtMs <= 0L) return false
        val pausedForMs = SystemClock.elapsedRealtime() - state.pausedAtMs
        return pausedForMs in 0..state.cardPauseHoldMs
    }

    fun remainingCardPauseHoldMs(state: PlaybackUiState = uiState.value): Long {
        if (state.isPlaying || state.cardPauseHoldMs <= 0L || state.pausedAtMs <= 0L) return 0L
        return (state.cardPauseHoldMs - (SystemClock.elapsedRealtime() - state.pausedAtMs)).coerceAtLeast(0L)
    }

    private fun registerBatteryReceiver(context: Context) {
        if (batteryReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(batteryReceiver, filter)
        }
        batteryReceiverRegistered = true
        updateBatteryState(batteryIntent)
    }

    private fun updateBatteryState(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return
        val percent = ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
        mutableUiState.update { state ->
            state.copy(
                batteryPercent = percent,
                cardsDisabledForBattery = percent <= 20
            )
        }
    }

    private fun requestArtworkLoad(
        signature: String,
        cacheKeys: List<String>,
        uri: String?,
        fallbackBitmap: Bitmap?
    ) {
        if (signature.isBlank() || (uri.isNullOrBlank() && fallbackBitmap == null)) return
        val context = appContext ?: return
        val loader = imageLoader ?: return
        val requestKey = listOf(
            signature,
            uri.orEmpty(),
            fallbackBitmap?.width?.toString().orEmpty(),
            fallbackBitmap?.height?.toString().orEmpty()
        ).joinToString("|")
        if (activeArtworkRequestKey == requestKey) return
        if (requestKey in badArtworkRequestKeys) return
        activeArtworkRequestKey = requestKey

        artworkScope.launch {
            val loadedArtwork = runCatching {
                if (!uri.isNullOrBlank()) {
                    loadArtworkFromUri(context, loader, signature, uri) ?: fallbackBitmap.safeResizedForWallpaper()
                } else {
                    fallbackBitmap.safeResizedForWallpaper()
                }
            }.getOrNull()

            if (loadedArtwork != null) {
                ArtworkCache.storeAsync(cacheKeys, loadedArtwork)
                mutableUiState.update { state ->
                    if (state.trackSignature != signature) {
                        state
                    } else {
                        val chosenArtwork = if (state.artworkSignature == signature) {
                            chooseSessionArtwork(state.artworkBitmap, loadedArtwork)
                        } else {
                            loadedArtwork
                        }
                        if (chosenArtwork === state.artworkBitmap && state.artworkSignature == signature) {
                            state
                        } else {
                            state.copy(
                                artworkBitmap = chosenArtwork,
                                artworkSignature = signature
                            )
                        }
                    }
                }
            } else {
                rememberBadArtworkRequest(requestKey)
            }

            if (activeArtworkRequestKey == requestKey) {
                activeArtworkRequestKey = null
            }
        }
    }

    private suspend fun loadArtworkFromUri(
        context: Context,
        loader: ImageLoader,
        signature: String,
        uri: String
    ): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(TARGET_ARTWORK_SIZE, TARGET_ARTWORK_SIZE)
            .allowHardware(false)
            .memoryCacheKey(signature)
            .diskCacheKey(signature)
            .build()
        val result = loader.execute(request) as? SuccessResult ?: return null
        val drawable = result.drawable
        val bitmap = if (drawable is BitmapDrawable) {
            drawable.bitmap
        } else {
            drawable.toBitmap()
        }
        return bitmap.safeResizedForWallpaper()
    }

    private fun Bitmap.resizedForWallpaper(): Bitmap {
        require(!isRecycled && width > 0 && height > 0) { "Invalid artwork bitmap" }
        val largestEdge = max(width, height).coerceAtLeast(1)
        if (largestEdge <= TARGET_ARTWORK_SIZE) return this
        val scale = TARGET_ARTWORK_SIZE.toFloat() / largestEdge.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun Bitmap?.safeResizedForWallpaper(): Bitmap? {
        return runCatching {
            this?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
                ?.resizedForWallpaper()
        }.getOrNull()
    }

    private fun MediaMetadata.preferredArtworkUri(): String? {
        return safeString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).takeIf { it.isNotBlank() }
            ?: safeString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).takeIf { it.isNotBlank() }
            ?: safeString(MediaMetadata.METADATA_KEY_ART_URI).takeIf { it.isNotBlank() }
    }

    private fun MediaMetadata?.safeString(key: String): String {
        return runCatching { this?.getString(key).orEmpty() }.getOrDefault("")
    }

    private fun MediaMetadata?.safeLong(key: String): Long? {
        return runCatching { this?.getLong(key) }.getOrNull()
    }

    private fun MediaMetadata?.safeBitmap(key: String): Bitmap? {
        return runCatching {
            this?.getBitmap(key)?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
        }.getOrNull()
    }

    private fun artworkCacheKeys(
        packageName: String,
        title: String,
        artist: String,
        album: String
    ): List<String> {
        val normalizedPackage = canonicalizeCachePart(packageName, preserveSymbols = true)
        val normalizedTitle = canonicalizeCachePart(title)
        val normalizedArtist = canonicalizeCachePart(artist)
        val normalizedAlbum = canonicalizeCachePart(album)
        return buildList {
            add(listOf(normalizedPackage, normalizedTitle, normalizedArtist).joinToString("|"))
            if (normalizedAlbum.isNotBlank()) {
                add(listOf(normalizedPackage, normalizedTitle, normalizedArtist, normalizedAlbum).joinToString("|"))
            }
            if (normalizedTitle.isNotBlank() && normalizedArtist.isNotBlank()) {
                add(listOf(normalizedTitle, normalizedArtist).joinToString("|global_title_artist|"))
            }
            if (normalizedTitle.isNotBlank() && normalizedArtist.isNotBlank()) {
                add(listOf(normalizedPackage, normalizedTitle, normalizedArtist).joinToString("|title_artist|"))
            }
            if (normalizedTitle.isNotBlank()) {
                add(normalizedTitle)
                add(listOf(normalizedPackage, normalizedTitle).joinToString("|title|"))
            }
            if (normalizedArtist.isNotBlank()) {
                add(listOf(normalizedArtist, normalizedTitle).joinToString("|artist_title|"))
            }
            if (normalizedAlbum.isNotBlank()) {
                add(normalizedAlbum)
                add(listOf(normalizedPackage, normalizedAlbum).joinToString("|album|"))
                if (normalizedArtist.isNotBlank()) {
                    add(listOf(normalizedArtist, normalizedAlbum).joinToString("|global_artist_album|"))
                    add(listOf(normalizedPackage, normalizedArtist, normalizedAlbum).joinToString("|artist_album|"))
                }
            }
        }.distinct()
    }

    private fun playbackDeviceLabel(controller: MediaController): String {
        val info = controller.playbackInfo
        if (info != null && info.playbackType == android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
            return "Remote output"
        }
        val context = appContext ?: return "This device"
        val deviceName = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        return deviceName?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() }
            ?: "This device"
    }

    private fun readableSourceName(packageName: String): String {
        return when (packageName) {
            APPLE_MUSIC_PACKAGE -> "Apple Music"
            "com.spotify.music" -> "Spotify"
            "com.google.android.apps.youtube.music" -> "YouTube Music"
            "com.gaana" -> "Gaana"
            "in.amazon.mShop.android.shopping" -> "Amazon Music"
            else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifBlank { "Unknown player" }
        }
    }

    private fun inferExplicitFlag(
        title: String,
        metadata: MediaMetadata?,
        extras: Bundle?
    ): Boolean {
        val explicitExtraKeys = listOf(
            "android.media.IS_EXPLICIT",
            "android.media.metadata.IS_EXPLICIT",
            "android.media.extra.IS_EXPLICIT",
            "androidx.media.IS_EXPLICIT",
            "is_explicit",
            "isExplicit"
        )

        val extrasFlag = runCatching {
            extras?.let { bundle ->
            explicitExtraKeys.any { key ->
                bundleValueMeansExplicit(bundle, key)
            } || bundle.keySet().any { key ->
                val normalized = key.lowercase()
                val looksRelevant = normalized.contains("explicit") ||
                    normalized.contains("advisory") ||
                    normalized.contains("rating")
                looksRelevant && bundleValueMeansExplicit(bundle, key)
            }
            } ?: false
        }.getOrDefault(false)
        if (extrasFlag) return true

        val metadataFlag = runCatching {
            metadata?.run {
            keySet().any { key ->
                val normalized = key.lowercase()
                val looksRelevant = normalized.contains("explicit") ||
                    normalized.contains("advisory") ||
                    normalized.contains("rating")
                if (!looksRelevant) return@any false
                safeLong(key) == 1L || safeString(key).isExplicitText()
            }
            } ?: false
        }.getOrDefault(false)
        if (metadataFlag) return true

        return title.hasExplicitMarker()
    }

    private fun bundleValueMeansExplicit(bundle: Bundle, key: String): Boolean {
        if (!bundle.containsKey(key)) return false
        return when (val value = runCatching { bundle.get(key) }.getOrNull()) {
            is Boolean -> value
            is Int -> value == 1
            is Long -> value == 1L
            is String -> value.isExplicitText()
            is CharSequence -> value.toString().isExplicitText()
            else -> false
        }
    }

    private fun String?.isExplicitText(): Boolean {
        val text = this?.trim().orEmpty()
        if (text.isBlank()) return false
        return text.equals("true", ignoreCase = true) ||
            text == "1" ||
            text.equals("yes", ignoreCase = true) ||
            text.contains("explicit", ignoreCase = true)
    }

    private fun String.hasExplicitMarker(): Boolean {
        return Regex("""(^|[\s\[\(\-])(?:E|Explicit)([\s\]\)\-]|$)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(this)
    }

    private fun normalizedPauseHoldMs(durationMs: Long): Long {
        return CARD_PAUSE_HOLD_OPTIONS_MS.minByOrNull { abs(it - durationMs) } ?: 0L
    }

    private fun persistLayout() {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val state = mutableUiState.value
        prefs.edit()
            .putFloat(KEY_CARD_X, state.cardOffsetX)
            .putFloat(KEY_CARD_Y, state.cardOffsetY)
            .putFloat(KEY_CARD_SCALE, state.cardScale)
            .putFloat(KEY_PLAYER_CARD_WIDTH, state.playerCardWidthScale)
            .putFloat(KEY_PLAYER_CARD_OFFSET_Y, state.playerCardOffsetY)
            .putFloat(KEY_CARD_RADIUS, state.cardCornerRadius)
            .putFloat(KEY_PLAYER_CARD_FROST, state.playerCardFrost)
            .putBoolean(KEY_SHOW_CARD_ON_LOCK_SCREEN, state.showCardOnLockScreen)
            .putBoolean(KEY_SHOW_CARD_ON_HOME_SCREEN, state.showCardOnHomeScreen)
            .putLong(KEY_CARD_PAUSE_HOLD_MS, state.cardPauseHoldMs)
            .putFloat(KEY_TEXT_X, state.textOffsetX)
            .putFloat(KEY_TEXT_Y, state.textOffsetY)
            .putFloat(KEY_TITLE_TEXT_SCALE, state.titleTextScale)
            .putFloat(KEY_ARTIST_TEXT_SCALE, state.artistTextScale)
            .putFloat(KEY_BLUR_AMOUNT, state.blurAmount)
            .putFloat(KEY_FLUID_SCALE, state.fluidScale)
            .putFloat(KEY_FLUIDITY, state.fluidity)
            .putFloat(KEY_GRADIENT_BRIGHTNESS, state.gradientBrightness)
            .putBoolean(KEY_PRESERVE_ARTWORK_ON_REBOOT, state.preserveArtworkOnReboot)
            .putString(KEY_TEXT_ALIGNMENT, state.textAlignment.name)
            .apply()
    }

    private fun canonicalizeCachePart(value: String, preserveSymbols: Boolean = false): String {
        val trimmed = value.trim().lowercase()
        if (trimmed.isBlank()) return ""
        val normalized = if (preserveSymbols) {
            trimmed
        } else {
            trimmed.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        }
        return normalized.replace(Regex("\\s+"), " ").trim()
    }

    private fun cachedDurationMs(cacheKeys: Collection<String>): Long? {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return null
        cacheKeys.forEach { key ->
            val duration = prefs.getLong(durationCacheKey(key), 0L)
            if (duration > 0L) return duration
        }
        return null
    }

    private fun rememberDuration(cacheKeys: Collection<String>, durationMs: Long) {
        if (durationMs <= 0L) return
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val editor = prefs.edit()
        cacheKeys.take(4).forEach { key ->
            if (key.isNotBlank()) {
                editor.putLong(durationCacheKey(key), durationMs)
            }
        }
        editor.apply()
    }

    private fun durationCacheKey(signature: String): String {
        return "$DURATION_CACHE_PREFIX$signature"
    }

    private fun chooseSharperArtwork(current: Bitmap?, candidate: Bitmap?): Bitmap? {
        val safeCandidate = candidate?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 } ?: return current
        val safeCurrent = current?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
        if (safeCurrent == null) return safeCandidate
        val currentPixels = safeCurrent.width * safeCurrent.height
        val candidatePixels = safeCandidate.width * safeCandidate.height
        return if (candidatePixels >= (currentPixels * 1.18f).toInt()) safeCandidate else safeCurrent
    }

    private fun chooseSessionArtwork(current: Bitmap?, candidate: Bitmap): Bitmap {
        val safeCandidate = candidate.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 } ?: return current ?: candidate
        val safeCurrent = current?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 } ?: return safeCandidate
        val currentPixels = safeCurrent.width * safeCurrent.height
        val candidatePixels = safeCandidate.width * safeCandidate.height
        return if (candidatePixels >= (currentPixels * 1.10f).toInt()) {
            safeCandidate
        } else {
            safeCurrent
        }
    }

    private fun PlaybackState?.playbackSpeedFor(isPlayingNow: Boolean): Float {
        if (!isPlayingNow || this == null) return 0f
        return playbackSpeed.takeIf { it > 0f } ?: 1f
    }

    private fun PlaybackState.currentPositionMs(speed: Float): Long? {
        val basePosition = position.takeIf { it >= 0L } ?: return null
        val updatedAt = lastPositionUpdateTime
        if (speed <= 0f || updatedAt <= 0L) return basePosition
        val elapsedSincePlayerUpdate = ((SystemClock.elapsedRealtime() - updatedAt).coerceAtLeast(0L) * speed).toLong()
        return basePosition + elapsedSincePlayerUpdate
    }

    private fun rememberBadArtworkRequest(requestKey: String) {
        if (requestKey.isBlank()) return
        synchronized(badArtworkRequestKeys) {
            badArtworkRequestKeys += requestKey
            while (badArtworkRequestKeys.size > 16) {
                badArtworkRequestKeys.remove(badArtworkRequestKeys.first())
            }
        }
    }

    private fun requirePrefs() =
        checkNotNull(appContext) { "PlaybackRepository.initialize must be called first." }
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
