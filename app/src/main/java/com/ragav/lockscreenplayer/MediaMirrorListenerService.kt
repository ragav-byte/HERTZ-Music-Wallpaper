package com.ragav.lockscreenplayer

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
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
    @Volatile
    private var activeMediaPackage: String? = null

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
        scheduleRefreshBurst()
    }

    override fun onListenerDisconnected() {
        runCatching {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        refreshBurstJob?.cancel()
        activeMediaPackage = null
        PlaybackRepository.attachController(null)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            safePrimeNotificationText(it)
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
            val refreshOffsetsMs = listOf(0L, 120L, 350L, 800L, 1_500L, 2_250L)
            refreshOffsetsMs.forEachIndexed { index, offsetMs ->
                if (index > 0) {
                    val previous = refreshOffsetsMs[index - 1]
                    delay(offsetMs - previous)
                }
                safeRefreshSessions()
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
            artwork = null,
            isExplicit = notificationExplicitFlag(extras, title)
        )
    }

    private fun notificationExplicitFlag(extras: Bundle, title: String?): Boolean {
        val keys = listOf(
            "android.media.IS_EXPLICIT",
            "android.media.metadata.IS_EXPLICIT",
            "android.media.extra.IS_EXPLICIT",
            "androidx.media.IS_EXPLICIT",
            "is_explicit",
            "isExplicit"
        )
        val exactMatch = keys.any { key -> bundleValueMeansExplicit(extras, key) }
        if (exactMatch) return true

        val fuzzyMatch = extras.keySet().any { key ->
            val normalized = key.lowercase()
            val looksRelevant = normalized.contains("explicit") ||
                normalized.contains("advisory") ||
                normalized.contains("rating")
            looksRelevant && bundleValueMeansExplicit(extras, key)
        }
        if (fuzzyMatch) return true

        return title.orEmpty()
            .contains(Regex("""(^|[\s\[\(\-])(?:E|Explicit)([\s\]\)\-]|$)""", RegexOption.IGNORE_CASE))
    }

    private fun bundleValueMeansExplicit(bundle: Bundle, key: String): Boolean {
        if (!bundle.containsKey(key)) return false
        return when (val value = bundle.get(key)) {
            is Boolean -> value
            is Int -> value == 1
            is Long -> value == 1L
            is String -> value.isExplicitText()
            is CharSequence -> value.toString().isExplicitText()
            else -> false
        }
    }

    private fun String.isExplicitText(): Boolean {
        val text = trim()
        return text.equals("true", ignoreCase = true) ||
            text == "1" ||
            text.equals("yes", ignoreCase = true) ||
            text.contains("explicit", ignoreCase = true)
    }

    private fun safePrimeNotificationText(sbn: StatusBarNotification) {
        runCatching { primeNotificationText(sbn) }
    }

    private fun isLikelyMediaNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        val extras = notification.extras ?: return sbn.packageName in KNOWN_PLAYER_PACKAGES
        return sbn.packageName in KNOWN_PLAYER_PACKAGES ||
            notification.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey(MEDIA_SESSION_EXTRA_KEY)
    }
}
