package com.ragav.lockscreenplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlaybackUiState(
    val title: String = "Start Apple Music",
    val artist: String = "The current song will appear here",
    val album: String = "",
    val sourceApp: String = "No player detected",
    val sourcePackage: String = "",
    val playbackDeviceLabel: String = "This device",
    val artworkBitmap: Bitmap? = null,
    val hasSourceSession: Boolean = false,
    val isPlaying: Boolean = false,
    val pausedAtMs: Long = 0L,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val positionCapturedAtMs: Long = 0L,
    val cardOffsetX: Float = 0f,
    val cardOffsetY: Float = 0f,
    val cardScale: Float = 0.56f,
    val playerCardWidthScale: Float = 0.84f,
    val cardCornerRadius: Float = 0.16f,
    val playerCardFrost: Float = 0.58f,
    val textOffsetX: Float = 0f,
    val textOffsetY: Float = 0f,
    val titleTextScale: Float = 1.0f,
    val artistTextScale: Float = 1.08f,
    val blurAmount: Float = 0.72f,
    val fluidScale: Float = 0.82f,
    val fluidity: Float = 0.62f,
    val textAlignment: TextAlignmentOption = TextAlignmentOption.CENTER,
    val trackSignature: String = ""
)

enum class TextAlignmentOption {
    LEFT,
    CENTER,
    RIGHT
}

object PlaybackRepository {
    const val CARD_HIDE_DELAY_MS = 30_000L
    private const val CARD_STEP = 0.12f
    private const val TEXT_STEP = 0.08f
    private const val MIN_CARD_SCALE = 0.34f
    private const val MAX_CARD_SCALE = 0.88f
    private const val MIN_PLAYER_CARD_WIDTH = 0.56f
    private const val MAX_PLAYER_CARD_WIDTH = 0.96f
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
    private const val KEY_CARD_RADIUS = "card_radius"
    private const val KEY_PLAYER_CARD_FROST = "player_card_frost"
    private const val KEY_TEXT_X = "text_x"
    private const val KEY_TEXT_Y = "text_y"
    private const val KEY_TITLE_TEXT_SCALE = "title_text_scale"
    private const val KEY_ARTIST_TEXT_SCALE = "artist_text_scale"
    private const val KEY_BLUR_AMOUNT = "blur_amount"
    private const val KEY_FLUID_SCALE = "fluid_scale"
    private const val KEY_FLUIDITY = "fluidity"
    private const val KEY_TEXT_ALIGNMENT = "text_alignment"
    private const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"

    private val mutableUiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = mutableUiState.asStateFlow()

    private var currentController: MediaController? = null
    private var appContext: Context? = null

    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val prefs = requirePrefs()
        mutableUiState.value = mutableUiState.value.copy(
            cardOffsetX = prefs.getFloat(KEY_CARD_X, 0f).coerceIn(CARD_X_MIN, CARD_X_MAX),
            cardOffsetY = prefs.getFloat(KEY_CARD_Y, 0f).coerceIn(CARD_Y_MIN, CARD_Y_MAX),
            cardScale = prefs.getFloat(KEY_CARD_SCALE, 0.56f).coerceIn(MIN_CARD_SCALE, MAX_CARD_SCALE),
            playerCardWidthScale = prefs.getFloat(KEY_PLAYER_CARD_WIDTH, 0.84f)
                .coerceIn(MIN_PLAYER_CARD_WIDTH, MAX_PLAYER_CARD_WIDTH),
            cardCornerRadius = prefs.getFloat(KEY_CARD_RADIUS, 0.16f).coerceIn(MIN_CARD_RADIUS, MAX_CARD_RADIUS),
            playerCardFrost = prefs.getFloat(KEY_PLAYER_CARD_FROST, 0.58f)
                .coerceIn(MIN_PLAYER_CARD_FROST, MAX_PLAYER_CARD_FROST),
            textOffsetX = prefs.getFloat(KEY_TEXT_X, 0f).coerceIn(TEXT_X_MIN, TEXT_X_MAX),
            textOffsetY = prefs.getFloat(KEY_TEXT_Y, 0f).coerceIn(TEXT_Y_MIN, TEXT_Y_MAX),
            titleTextScale = prefs.getFloat(KEY_TITLE_TEXT_SCALE, 1.0f).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
            artistTextScale = prefs.getFloat(KEY_ARTIST_TEXT_SCALE, 1.08f).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
            blurAmount = prefs.getFloat(KEY_BLUR_AMOUNT, 0.72f).coerceIn(0f, 1f),
            fluidScale = prefs.getFloat(KEY_FLUID_SCALE, 0.82f).coerceIn(0f, 1f),
            fluidity = prefs.getFloat(KEY_FLUIDITY, 0.62f).coerceIn(0f, 1f),
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
                cardOffsetY = 0f,
                cardScale = 0.56f,
                playerCardWidthScale = 0.84f,
                cardCornerRadius = 0.16f,
                playerCardFrost = 0.58f,
                textOffsetX = 0f,
                textOffsetY = 0f,
                titleTextScale = 1.0f,
                artistTextScale = 1.08f,
                blurAmount = 0.72f,
                fluidScale = 0.82f,
                fluidity = 0.62f,
                textAlignment = TextAlignmentOption.CENTER
            )
        }
        persistLayout()
    }

    fun primeFromNotification(
        packageName: String,
        title: String?,
        artist: String?,
        artwork: Bitmap?
    ) {
        val cleanTitle = title?.trim().orEmpty()
        val cleanArtist = artist?.trim().orEmpty()
        if (cleanTitle.isBlank() && cleanArtist.isBlank() && artwork == null) return

        mutableUiState.update { state ->
            val shouldAccept = state.sourcePackage.isBlank() ||
                state.sourcePackage == packageName ||
                packageName == APPLE_MUSIC_PACKAGE

            if (!shouldAccept) {
                state
            } else {
                val updatedTitle = cleanTitle.ifBlank { state.title }
                val updatedArtist = cleanArtist.ifBlank { state.artist }
                state.copy(
                    title = updatedTitle,
                    artist = updatedArtist,
                    sourceApp = if (state.sourcePackage.isBlank()) readableSourceName(packageName) else state.sourceApp,
                    sourcePackage = if (state.sourcePackage.isBlank()) packageName else state.sourcePackage,
                    artworkBitmap = artwork ?: state.artworkBitmap,
                    trackSignature = listOf(
                        if (state.sourcePackage.isBlank()) packageName else state.sourcePackage,
                        updatedTitle,
                        updatedArtist,
                        state.album
                    ).joinToString("|")
                )
            }
        }
    }

    private fun syncFromController(controller: MediaController?) {
        if (controller == null) {
            mutableUiState.update { state ->
                state.copy(
                    hasSourceSession = false,
                    isPlaying = false,
                    pausedAtMs = SystemClock.elapsedRealtime()
                )
            }
            return
        }

        val metadata = controller.metadata
        val description = metadata?.description
        val packageName = controller.packageName.orEmpty()
        val playbackState = controller.playbackState
        val playbackStateCode = playbackState?.state
        val trackTitle = description?.title?.toString().orEmpty().ifBlank { uiState.value.title }
        val trackArtist = description?.subtitle?.toString().orEmpty().ifBlank { uiState.value.artist }
        val trackAlbum = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val position = playbackState?.position?.coerceAtLeast(0L) ?: uiState.value.positionMs
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L)
            ?: uiState.value.durationMs
        val signature = listOf(packageName, trackTitle, trackArtist, trackAlbum).joinToString("|")

        mutableUiState.update { state ->
            val isPlayingNow =
                playbackStateCode == PlaybackState.STATE_PLAYING || playbackStateCode == PlaybackState.STATE_BUFFERING
            state.copy(
                title = trackTitle,
                artist = trackArtist,
                album = trackAlbum,
                sourceApp = readableSourceName(packageName),
                sourcePackage = packageName,
                playbackDeviceLabel = playbackDeviceLabel(controller),
                artworkBitmap = artwork ?: state.artworkBitmap,
                hasSourceSession = true,
                isPlaying = isPlayingNow,
                pausedAtMs = if (isPlayingNow) 0L else if (state.pausedAtMs == 0L) SystemClock.elapsedRealtime() else state.pausedAtMs,
                durationMs = duration,
                positionMs = position,
                positionCapturedAtMs = SystemClock.elapsedRealtime(),
                trackSignature = signature
            )
        }
    }

    fun shouldShowCard(state: PlaybackUiState = uiState.value, nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (state.isPlaying) return true
        if (!state.hasSourceSession) return false
        val pausedAt = state.pausedAtMs
        if (pausedAt == 0L) return false
        return nowMs - pausedAt < CARD_HIDE_DELAY_MS
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

    private fun persistLayout() {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val state = mutableUiState.value
        prefs.edit()
            .putFloat(KEY_CARD_X, state.cardOffsetX)
            .putFloat(KEY_CARD_Y, state.cardOffsetY)
            .putFloat(KEY_CARD_SCALE, state.cardScale)
            .putFloat(KEY_PLAYER_CARD_WIDTH, state.playerCardWidthScale)
            .putFloat(KEY_CARD_RADIUS, state.cardCornerRadius)
            .putFloat(KEY_PLAYER_CARD_FROST, state.playerCardFrost)
            .putFloat(KEY_TEXT_X, state.textOffsetX)
            .putFloat(KEY_TEXT_Y, state.textOffsetY)
            .putFloat(KEY_TITLE_TEXT_SCALE, state.titleTextScale)
            .putFloat(KEY_ARTIST_TEXT_SCALE, state.artistTextScale)
            .putFloat(KEY_BLUR_AMOUNT, state.blurAmount)
            .putFloat(KEY_FLUID_SCALE, state.fluidScale)
            .putFloat(KEY_FLUIDITY, state.fluidity)
            .putString(KEY_TEXT_ALIGNMENT, state.textAlignment.name)
            .apply()
    }

    private fun requirePrefs() =
        checkNotNull(appContext) { "PlaybackRepository.initialize must be called first." }
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
