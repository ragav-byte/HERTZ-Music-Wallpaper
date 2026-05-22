package com.ragav.lockscreenplayer

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ragav.lockscreenplayer.data.PlaybackRepository
import com.ragav.lockscreenplayer.data.PlaybackUiState
import com.ragav.lockscreenplayer.data.TextAlignmentOption
import com.ragav.lockscreenplayer.ui.theme.LockscreenPlayerTheme

class MainActivity : ComponentActivity() {
    private var hasNotificationListenerAccess by mutableStateOf(false)
    private var statusMessage by mutableStateOf(
        "Enable media access, then start your player and set HERTZ as your wallpaper."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncPermissionState()

        setContent {
            LockscreenPlayerTheme {
                val playbackState by PlaybackRepository.uiState.collectAsStateWithLifecycle()

                WallpaperStudioScreen(
                    playbackState = playbackState,
                    hasNotificationListenerAccess = hasNotificationListenerAccess,
                    statusMessage = statusMessage,
                    onOpenListenerSettings = ::openListenerSettings,
                    onOpenAppSettings = ::openAppSettings,
                    onEnableLiveWallpaper = ::enableLiveWallpaper,
                    onOpenSourceApp = ::openSourceApp,
                    onOpenSupportEmail = ::openSupportEmail,
                    onSetCardOffset = PlaybackRepository::setCardOffset,
                    onSetCardScale = PlaybackRepository::setCardScale,
                    onSetPlayerCardWidthScale = PlaybackRepository::setPlayerCardWidthScale,
                    onSetPlayerCardOffsetY = PlaybackRepository::setPlayerCardOffsetY,
                    onSetCardCornerRadius = PlaybackRepository::setCardCornerRadius,
                    onSetPlayerCardFrost = PlaybackRepository::setPlayerCardFrost,
                    onSetShowCardOnLockScreen = PlaybackRepository::setShowCardOnLockScreen,
                    onSetShowCardOnHomeScreen = PlaybackRepository::setShowCardOnHomeScreen,
                    onSetTitleTextScale = PlaybackRepository::setTitleTextScale,
                    onSetArtistTextScale = PlaybackRepository::setArtistTextScale,
                    onSetBlurAmount = PlaybackRepository::setBlurAmount,
                    onSetTextAlignment = PlaybackRepository::setTextAlignment,
                    onResetLayout = PlaybackRepository::resetLayout
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncPermissionState()
        requestListenerRebindIfPossible()
                    statusMessage = when {
            !hasNotificationListenerAccess -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    "Enable notification access. If Android blocks it because this is a sideloaded app, open App settings, allow restricted settings, then return here."
                } else {
                    "Enable notification access so the wallpaper can read the current song."
                }
            }

            !PlaybackRepository.uiState.value.hasSourceSession -> {
                "Media access is ready. Start your music app and keep the player visible for a moment, then come back here."
            }

            else -> {
                "Current song detected from ${PlaybackRepository.uiState.value.sourceApp}. Tune the layout and set the wallpaper once."
            }
        }
    }

    private fun openListenerSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        statusMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "If the toggle is blocked, open App settings first, tap the top-right menu, allow restricted settings, then return here."
        } else {
            "Turn on notification access for this app, then come back here."
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
    }

    private fun enableLiveWallpaper() {
        if (!listenerAccessGranted()) {
            statusMessage = "Enable media access first so the wallpaper can react to the current song."
            return
        }

        val component = ComponentName(this, MusicCanvasWallpaperService::class.java)
        val directIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        val chooserIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)

        runCatching {
            startActivity(directIntent)
            statusMessage = "Choose HERTZ and apply it. Some phones let you use it on the lock screen only, while others apply it to home and lock screen together."
        }.recoverCatching {
            startActivity(chooserIntent)
            statusMessage = "Open HERTZ in the wallpaper picker and apply it from there."
        }.onFailure { error ->
            statusMessage = "Unable to open the live wallpaper picker: ${error.message ?: "Unknown error"}"
        }
    }

    private fun openSourceApp(packageName: String) {
        val targetPackage = packageName.ifBlank { "com.apple.android.music" }
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            statusMessage = "Couldn't open the music app from this phone right now."
        }
    }

    private fun openSupportEmail() {
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:ragavkrishna4535@gmail.com")
            putExtra(Intent.EXTRA_SUBJECT, "HERTZ Support / Report a problem")
        }
        runCatching {
            startActivity(emailIntent)
        }.onFailure {
            statusMessage = "No email app was found on this phone right now."
        }
    }

    private fun syncPermissionState() {
        hasNotificationListenerAccess = listenerAccessGranted()
    }

    private fun requestListenerRebindIfPossible() {
        if (!listenerAccessGranted()) return

        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(this, MediaMirrorListenerService::class.java)
            )
        }
    }

    private fun listenerAccessGranted(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val component = ComponentName(this, MediaMirrorListenerService::class.java)
        return enabledListeners.contains(component.flattenToString(), ignoreCase = true) ||
            enabledListeners.contains(component.flattenToShortString(), ignoreCase = true)
    }
}

@Composable
private fun WallpaperStudioScreen(
    playbackState: PlaybackUiState,
    hasNotificationListenerAccess: Boolean,
    statusMessage: String,
    onOpenListenerSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onEnableLiveWallpaper: () -> Unit,
    onOpenSourceApp: (String) -> Unit,
    onOpenSupportEmail: () -> Unit,
    onSetCardOffset: (Float, Float) -> Unit,
    onSetCardScale: (Float) -> Unit,
    onSetPlayerCardWidthScale: (Float) -> Unit,
    onSetPlayerCardOffsetY: (Float) -> Unit,
    onSetCardCornerRadius: (Float) -> Unit,
    onSetPlayerCardFrost: (Float) -> Unit,
    onSetShowCardOnLockScreen: (Boolean) -> Unit,
    onSetShowCardOnHomeScreen: (Boolean) -> Unit,
    onSetTitleTextScale: (Float) -> Unit,
    onSetArtistTextScale: (Float) -> Unit,
    onSetBlurAmount: (Float) -> Unit,
    onSetTextAlignment: (TextAlignmentOption) -> Unit,
    onResetLayout: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        ArtworkBackdrop(artworkBitmap = null)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HeroCard(
                title = "HERTZ",
                subtitle = "MUSIC WALLPAPER"
            )

            StatusCard(statusMessage = statusMessage)

            InfoCard(
                title = "Requirements",
                subtitle = if (hasNotificationListenerAccess) {
                    "The wallpaper is ready to read your active song."
                } else {
                    "Enable notification access so HERTZ can read the current song artwork and text."
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onEnableLiveWallpaper,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF111111)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Wallpaper,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Set wallpaper")
                    }

                    OutlinedButton(
                        onClick = onOpenListenerSettings,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Wallpaper,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (hasNotificationListenerAccess) "Manage media access" else "Enable media access")
                    }

                    OutlinedButton(
                        onClick = onOpenAppSettings,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("App settings")
                    }
                }
            }

            CurrentPlayingCard(
                playbackState = playbackState,
                onOpenSourceApp = onOpenSourceApp
            )

            InfoCard(
                title = "Surface behavior",
                subtitle = "Choose whether the card should appear on the lock screen, home screen, or both."
            ) {
                SurfaceCardChoiceRow(
                    label = "Lock screen card",
                    enabled = playbackState.showCardOnLockScreen,
                    onEnabledChange = onSetShowCardOnLockScreen
                )

                Spacer(modifier = Modifier.height(12.dp))

                SurfaceCardChoiceRow(
                    label = "Home screen card",
                    enabled = playbackState.showCardOnHomeScreen,
                    onEnabledChange = onSetShowCardOnHomeScreen
                )
            }

            InfoCard(
                title = "Configuration",
                subtitle = "Tune the layout and graphics. Motion freezes automatically when playback stops."
            ) {
                Text(
                    text = "Graphics settings",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                AbsoluteOffsetControls(
                    xLabel = "Card X",
                    yLabel = "Card Y",
                    xValue = playbackState.cardOffsetX,
                    yValue = playbackState.cardOffsetY,
                    xRange = -1.5f..1.5f,
                    yRange = -0.6f..3.2f,
                    onValueChange = onSetCardOffset
                )

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Card width ${(playbackState.cardScale * 100).toInt()}%",
                    color = Color(0xFFE8E8F0),
                    style = MaterialTheme.typography.titleMedium
                )
                AppSlider(
                    value = playbackState.cardScale,
                    onValueChange = onSetCardScale,
                    valueRange = 0.34f..0.88f
                )

                Text(
                    text = "Text card width ${(playbackState.playerCardWidthScale * 100).toInt()}%",
                    color = Color(0xFFE8E8F0),
                    style = MaterialTheme.typography.titleMedium
                )
                AppSlider(
                    value = playbackState.playerCardWidthScale,
                    onValueChange = onSetPlayerCardWidthScale,
                    valueRange = 0.56f..0.96f
                )

                Text(
                    text = "Text card Y ${(playbackState.playerCardOffsetY * 100).toInt()}%",
                    color = Color(0xFFE8E8F0),
                    style = MaterialTheme.typography.titleMedium
                )
                AppSlider(
                    value = playbackState.playerCardOffsetY,
                    onValueChange = onSetPlayerCardOffsetY,
                    valueRange = -1.0f..2.4f
                )

                Text(
                    text = "Card radius ${(playbackState.cardCornerRadius * 100).toInt()}%",
                    color = Color(0xFFE8E8F0),
                    style = MaterialTheme.typography.titleMedium
                )
                AppSlider(
                    value = playbackState.cardCornerRadius,
                    onValueChange = onSetCardCornerRadius,
                    valueRange = 0f..0.30f
                )

                Text(
                    text = "Glass frost ${(playbackState.playerCardFrost * 100).toInt()}%",
                    color = Color(0xFFE8E8F0),
                    style = MaterialTheme.typography.titleMedium
                )
                AppSlider(
                    value = playbackState.playerCardFrost,
                    onValueChange = onSetPlayerCardFrost,
                    valueRange = 0.15f..1f
                )

                Text(
                    text = "Song name size",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                SizePresetSelector(
                    selectedScale = playbackState.titleTextScale,
                    onScaleSelected = onSetTitleTextScale
                )

                Text(
                    text = "Artist name size",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                SizePresetSelector(
                    selectedScale = playbackState.artistTextScale,
                    onScaleSelected = onSetArtistTextScale
                )

                Text(
                    text = "Layer blur ${(playbackState.blurAmount * 100).toInt()}%",
                    color = Color(0xFFE8E8F0),
                    style = MaterialTheme.typography.titleMedium
                )
                AppSlider(
                    value = playbackState.blurAmount,
                    onValueChange = onSetBlurAmount,
                    valueRange = 0f..1f
                )

                Text(
                    text = "Text alignment",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AlignmentChoice(
                        label = "Left",
                        selected = playbackState.textAlignment == TextAlignmentOption.LEFT,
                        onClick = { onSetTextAlignment(TextAlignmentOption.LEFT) }
                    )
                    AlignmentChoice(
                        label = "Center",
                        selected = playbackState.textAlignment == TextAlignmentOption.CENTER,
                        onClick = { onSetTextAlignment(TextAlignmentOption.CENTER) }
                    )
                    AlignmentChoice(
                        label = "Right",
                        selected = playbackState.textAlignment == TextAlignmentOption.RIGHT,
                        onClick = { onSetTextAlignment(TextAlignmentOption.RIGHT) }
                    )
                }

                OutlinedButton(
                    onClick = onResetLayout,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CenterFocusStrong,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset layout")
                }
            }

            WallpaperPreviewCard(playbackState = playbackState)

            InfoCard(
                title = "Live wallpaper",
                subtitle = "Set it once from the wallpaper picker. After that, HERTZ updates when the song or layout changes."
            ) {
                Button(
                    onClick = onEnableLiveWallpaper,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF111111)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Wallpaper,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open wallpaper picker")
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Battery note: the motion only runs while the wallpaper is visible and music is actively playing. When the song pauses or the screen goes away, it settles into a static frame.",
                    color = Color(0xFFD2D1DB),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            InfoCard(
                title = "Support",
                subtitle = "Need help or want to report a problem?"
            ) {
                OutlinedButton(
                    onClick = onOpenSupportEmail,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
                ) {
                    Text("Email support")
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "This opens your email app with the receiver set to ragavkrishna4535@gmail.com.",
                    color = Color(0xFFD2D1DB),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AbsoluteOffsetControls(
    xLabel: String,
    yLabel: String,
    xValue: Float,
    yValue: Float,
    xRange: ClosedFloatingPointRange<Float>,
    yRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float, Float) -> Unit
) {
    Text(
        text = "$xLabel ${(xValue * 100).toInt()}%",
        color = Color(0xFFE8E8F0),
        style = MaterialTheme.typography.bodyMedium
    )
    AppSlider(
        value = xValue,
        onValueChange = { onValueChange(it, yValue) },
        valueRange = xRange
    )

    Text(
        text = "$yLabel ${(yValue * 100).toInt()}%",
        color = Color(0xFFE8E8F0),
        style = MaterialTheme.typography.bodyMedium
    )
    AppSlider(
        value = yValue,
        onValueChange = { onValueChange(xValue, it) },
        valueRange = yRange
    )
}

@Composable
private fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.6f),
            activeTickColor = Color.White,
            inactiveTickColor = Color.White.copy(alpha = 0.6f)
        )
    )
}

@Composable
private fun ArtworkBackdrop(artworkBitmap: Bitmap?) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (artworkBitmap != null) {
            Image(
                bitmap = artworkBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF121217))
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xE8000000),
                            Color(0xCE09040B),
                            Color(0xE80E0403)
                        )
                    )
                )
        )
    }
}

@Composable
private fun HeroCard(
    title: String,
    subtitle: String
) {
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xAA08080A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFD2D1DB)
            )
        }
    }
}

@Composable
private fun StatusCard(statusMessage: String) {
    Card(
        modifier = Modifier.border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xA31B1111))
    ) {
        Text(
            text = statusMessage,
            color = Color(0xFFF5E9E5),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
    }
}

@Composable
private fun CurrentPlayingCard(
    playbackState: PlaybackUiState,
    onOpenSourceApp: (String) -> Unit
) {
    InfoCard(
        title = "Current playing",
        subtitle = if (playbackState.hasSourceSession) {
            "Colors and motion are pulled from the current cover art."
        } else {
            "Start Apple Music and the artwork will appear here."
        }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(0.42f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF141414)),
                contentAlignment = Alignment.Center
            ) {
                val albumArt = playbackState.artworkBitmap
                if (albumArt != null) {
                    Image(
                        bitmap = albumArt.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = "No art",
                        color = Color(0xFFBFBCCB),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Column(
                modifier = Modifier.weight(0.58f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = playbackState.title.uppercase(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 21.sp * playbackState.titleTextScale
                    )
                )
                Text(
                    text = playbackState.artist,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp * playbackState.artistTextScale
                    )
                )
                Text(
                    text = displayPlaybackPosition(playbackState),
                    color = Color(0xFFE7E5F4),
                    style = MaterialTheme.typography.bodyLarge
                )

                Button(
                    onClick = { onOpenSourceApp(playbackState.sourcePackage) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF111111)
                    )
                ) {
                    Text("Open music application")
                }
            }
        }
    }
}

@Composable
private fun SizePresetSelector(
    selectedScale: Float,
    onScaleSelected: (Float) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SizePresetChoice(
            label = "S",
            selected = selectedScale <= 0.9f,
            onClick = { onScaleSelected(0.85f) }
        )
        SizePresetChoice(
            label = "M",
            selected = selectedScale > 0.9f && selectedScale < 1.1f,
            onClick = { onScaleSelected(1.0f) }
        )
        SizePresetChoice(
            label = "L",
            selected = selectedScale >= 1.1f,
            onClick = { onScaleSelected(1.18f) }
        )
    }
}

@Composable
private fun SizePresetChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF111111)
            )
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
        ) {
            Text(label)
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xB008080A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD2D1DB)
            )
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun AlignmentChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF111111)
            )
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
        ) {
            Text(label)
        }
    }
}

@Composable
private fun SurfaceCardChoiceRow(
    label: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (enabled) {
                Button(
                    onClick = { onEnabledChange(true) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF111111)
                    )
                ) {
                    Text("Show")
                }
            } else {
                OutlinedButton(
                    onClick = { onEnabledChange(true) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
                ) {
                    Text("Show")
                }
            }

            if (!enabled) {
                Button(
                    onClick = { onEnabledChange(false) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF111111)
                    )
                ) {
                    Text("Hide")
                }
            } else {
                OutlinedButton(
                    onClick = { onEnabledChange(false) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
                ) {
                    Text("Hide")
                }
            }
        }
    }
}

@Composable
private fun WallpaperPreviewCard(playbackState: PlaybackUiState) {
    val context = LocalContext.current
    val previewBitmap = remember(
        playbackState.title,
        playbackState.artist,
        playbackState.album,
        playbackState.artworkBitmap,
        playbackState.playbackDeviceLabel,
        playbackState.cardOffsetX,
        playbackState.cardOffsetY,
        playbackState.cardScale,
        playbackState.cardCornerRadius,
        playbackState.textOffsetX,
        playbackState.textOffsetY,
        playbackState.titleTextScale,
        playbackState.artistTextScale,
        playbackState.blurAmount,
        playbackState.fluidScale,
        playbackState.textAlignment,
        playbackState.fluidity,
        playbackState.isPlaying,
        playbackState.durationMs,
        playbackState.positionMs,
        playbackState.positionCapturedAtMs,
        playbackState.trackSignature,
        playbackState.playerCardWidthScale,
        playbackState.playerCardOffsetY,
        playbackState.playerCardFrost,
        playbackState.showCardOnLockScreen,
        playbackState.showCardOnHomeScreen
    ) {
        LiveWallpaperRenderer.render(
            context = context,
            state = playbackState,
            width = 1080,
            height = 2340,
            phase = if (playbackState.isPlaying) playbackState.fluidity * 4f else 0f,
            drawCards = playbackState.showCardOnLockScreen || playbackState.showCardOnHomeScreen
        )
    }

    Card(
        modifier = Modifier.border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(30.dp)),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xB008080A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Preview",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(240.dp)
                        .aspectRatio(9f / 19.5f)
                        .clip(RoundedCornerShape(34.dp))
                        .background(Color(0xFF020202))
                        .padding(8.dp)
                ) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "The preview uses the same renderer as the live wallpaper, so cover framing, text sizing, alignment, and fluidity all match what you apply.",
                color = Color(0xFFD2D1DB),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun displayPlaybackPosition(playbackState: PlaybackUiState): String {
    val effectiveDuration = playbackState.durationMs.coerceAtLeast(0L)
    val elapsed = if (playbackState.isPlaying) {
        (SystemClock.elapsedRealtime() - playbackState.positionCapturedAtMs).coerceAtLeast(0L)
    } else {
        0L
    }
    val current = (playbackState.positionMs + elapsed).coerceAtMost(effectiveDuration.takeIf { it > 0 } ?: Long.MAX_VALUE)
    return if (effectiveDuration > 0L) {
        "${displayTime(current)} / ${displayTime(effectiveDuration)}"
    } else {
        displayTime(current)
    }
}

private fun displayTime(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0:00"
    val totalSeconds = milliseconds / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
