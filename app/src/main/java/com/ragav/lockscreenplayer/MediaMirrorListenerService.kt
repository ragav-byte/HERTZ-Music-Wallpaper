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

class MediaMirrorListenerService : NotificationListenerService() {
    private lateinit var mediaSessionManager: MediaSessionManager

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        PlaybackRepository.attachController(selectPreferredController(controllers.orEmpty()))
    }

    override fun onCreate() {
        super.onCreate()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val component = ComponentName(this, MediaMirrorListenerService::class.java)
        mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
        PlaybackRepository.attachController(selectPreferredController(mediaSessionManager.getActiveSessions(component)))
    }

    override fun onListenerDisconnected() {
        runCatching {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        PlaybackRepository.attachController(null)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let(::primeFromNotification)
        refreshSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshSessions()
    }

    private fun refreshSessions() {
        val component = ComponentName(this, MediaMirrorListenerService::class.java)
        PlaybackRepository.attachController(selectPreferredController(mediaSessionManager.getActiveSessions(component)))
    }

    private fun selectPreferredController(controllers: List<MediaController>): MediaController? {
        if (controllers.isEmpty()) return null

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

    private fun primeFromNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val artwork = extractArtwork(sbn.notification)
        PlaybackRepository.primeFromNotification(
            packageName = sbn.packageName,
            title = title,
            artist = artist,
            artwork = artwork
        )
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
        val width = intrinsicWidth.coerceAtLeast(1)
        val height = intrinsicHeight.coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
        }
    }

    companion object {
        private const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
    }
}
