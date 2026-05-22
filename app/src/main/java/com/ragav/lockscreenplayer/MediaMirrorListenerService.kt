package com.ragav.lockscreenplayer

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ragav.lockscreenplayer.data.PlaybackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MediaMirrorListenerService : NotificationListenerService() {
    private companion object {
        private const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
        private const val MEDIA_SESSION_EXTRA_KEY = "android.mediaSession"
        private const val MAX_DRAWABLE_EDGE = 1600
        private val KNOWN_PLAYER_PACKAGES = setOf(
            APPLE_MUSIC_PACKAGE,
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.gaana"
        )
    }

    private lateinit var mediaSessionManager: MediaSessionManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshBurstJob: Job? = null
    private var continuousRefreshJob: Job? = null
    @Volatile
    private var activeMediaPackage: String? = null
    @Volatile
    private var lastArtworkNotificationKey: String? = null

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        val preferredController = selectPreferredController(controllers.orEmpty())
        activeMediaPackage = preferredController?.packageName
        PlaybackRepository.attachController(preferredController)
        scheduleRefreshBurst()
    }

    override fun onCreate() {
        super.onCreate()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val component = ComponentName(this, MediaMirrorListenerService::class.java)
        runCatching {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
        }
        safeRefreshSessions()
        startContinuousRefresh()
        scheduleRefreshBurst()
    }

    override fun onListenerDisconnected() {
        runCatching {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        refreshBurstJob?.cancel()
        continuousRefreshJob?.cancel()
        activeMediaPackage = null
        PlaybackRepository.attachController(null)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            safePrimeNotificationText(it)
            safePrimeNotificationArtworkAsync(it)
        }
        safeRefreshSessions()
        scheduleRefreshBurst()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        safeRefreshSessions()
        scheduleRefreshBurst()
    }

    override fun onDestroy() {
        refreshBurstJob?.cancel()
        continuousRefreshJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun refreshSessions() {
        val component = ComponentName(this, MediaMirrorListenerService::class.java)
        val preferredController = selectPreferredController(mediaSessionManager.getActiveSessions(component))
        activeMediaPackage = preferredController?.packageName
        PlaybackRepository.attachController(preferredController)
        primeFromActiveNotifications()
    }

    private fun safeRefreshSessions() {
        runCatching { refreshSessions() }
    }

    private fun scheduleRefreshBurst() {
        refreshBurstJob?.cancel()
        refreshBurstJob = serviceScope.launch {
            val refreshOffsetsMs = listOf(25L, 60L, 120L, 220L, 360L, 560L, 820L, 1_150L, 1_550L, 2_050L)
            refreshOffsetsMs.forEachIndexed { index, offsetMs ->
                if (index > 0) {
                    val previous = refreshOffsetsMs[index - 1]
                    delay(offsetMs - previous)
                }
                safeRefreshSessions()
            }
        }
    }

    private fun startContinuousRefresh() {
        if (continuousRefreshJob != null) return
        continuousRefreshJob = serviceScope.launch {
            while (true) {
                safeRefreshSessions()
                val delayMs = if (PlaybackRepository.uiState.value.hasSourceSession) 1_000L else 600L
                delay(delayMs)
            }
        }
    }

    private fun primeFromActiveNotifications() {
        runCatching {
            val latestMediaNotifications = activeNotifications
                ?.filter(::isLikelyMediaNotification)
                ?.filter { sbn ->
                    val preferredPackage = activeMediaPackage
                    preferredPackage == null || sbn.packageName == preferredPackage
                }
                ?.groupBy { it.packageName }
                ?.values
                ?.mapNotNull { notifications ->
                    notifications.maxByOrNull { it.postTime }
                }
                ?.sortedByDescending { it.postTime }
                .orEmpty()

            latestMediaNotifications.forEach { notification ->
                safePrimeNotificationText(notification)
                safePrimeNotificationArtworkAsync(notification)
            }
        }
    }

    private fun selectPreferredController(controllers: List<MediaController>): MediaController? {
        if (controllers.isEmpty()) return null

        val lockedPackage = activeMediaPackage
        if (lockedPackage != null) {
            val lockedPlaying = controllers.firstOrNull {
                it.packageName == lockedPackage &&
                    (it.playbackState?.state == PlaybackState.STATE_PLAYING || it.playbackState?.state == PlaybackState.STATE_BUFFERING)
            }
            if (lockedPlaying != null) return lockedPlaying

            val lockedAny = controllers.firstOrNull { it.packageName == lockedPackage }
            if (lockedAny != null) return lockedAny
        }

        val playingAppleMusic = controllers.firstOrNull {
            it.packageName == APPLE_MUSIC_PACKAGE && it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        if (playingAppleMusic != null) return playingAppleMusic

        val anyPlaying = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING || it.playbackState?.state == PlaybackState.STATE_BUFFERING
        }
        if (anyPlaying != null) return anyPlaying

        return controllers.firstOrNull { it.packageName == APPLE_MUSIC_PACKAGE } ?: controllers.first()
    }

    private fun primeNotificationText(sbn: StatusBarNotification) {
        if (!isLikelyMediaNotification(sbn)) return
        val preferredPackage = activeMediaPackage
        if (preferredPackage != null && sbn.packageName != preferredPackage) return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        PlaybackRepository.primeFromNotification(
            packageName = sbn.packageName,
            title = title,
            artist = artist,
            artwork = null
        )
    }

    private fun primeNotificationArtwork(sbn: StatusBarNotification) {
        if (!isLikelyMediaNotification(sbn)) return
        val preferredPackage = activeMediaPackage
        if (preferredPackage != null && sbn.packageName != preferredPackage) return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val artwork = extractArtwork(sbn.notification) ?: return
        PlaybackRepository.primeFromNotification(
            packageName = sbn.packageName,
            title = title,
            artist = artist,
            artwork = artwork
        )
    }

    private fun safePrimeNotificationText(sbn: StatusBarNotification) {
        runCatching { primeNotificationText(sbn) }
    }

    private fun safePrimeNotificationArtworkAsync(sbn: StatusBarNotification) {
        val key = notificationArtworkKey(sbn) ?: return
        if (lastArtworkNotificationKey == key) return
        lastArtworkNotificationKey = key
        serviceScope.launch {
            runCatching { primeNotificationArtwork(sbn) }
        }
    }

    private fun notificationArtworkKey(sbn: StatusBarNotification): String? {
        if (!isLikelyMediaNotification(sbn)) return null
        val extras = sbn.notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: ""
        return "${sbn.packageName}|$title|$artist|${sbn.postTime}"
    }

    @Suppress("DEPRECATION")
    private fun extractArtwork(notification: Notification): Bitmap? {
        val extras = notification.extras ?: return null
        val directBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
                ?: extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG, Bitmap::class.java)
                ?: extras.getParcelable(Notification.EXTRA_LARGE_ICON, Bitmap::class.java)
        } else {
            extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
                ?: extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG) as? Bitmap
                ?: extras.getParcelable(Notification.EXTRA_LARGE_ICON) as? Bitmap
        }
        if (directBitmap != null) return directBitmap

        val largeIconDrawable = notification.getLargeIcon()?.loadDrawable(this)
        if (largeIconDrawable != null) return largeIconDrawable.toBitmap()
        return null
    }

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap
        val rawWidth = intrinsicWidth.coerceAtLeast(1)
        val rawHeight = intrinsicHeight.coerceAtLeast(1)
        val scale = minOf(
            1f,
            MAX_DRAWABLE_EDGE.toFloat() / rawWidth.toFloat(),
            MAX_DRAWABLE_EDGE.toFloat() / rawHeight.toFloat()
        )
        val width = (rawWidth * scale).toInt().coerceAtLeast(1)
        val height = (rawHeight * scale).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
        }
    }

    private fun isLikelyMediaNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        val extras = notification.extras ?: return sbn.packageName in KNOWN_PLAYER_PACKAGES
        return sbn.packageName in KNOWN_PLAYER_PACKAGES ||
            notification.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey(MEDIA_SESSION_EXTRA_KEY)
    }
}
