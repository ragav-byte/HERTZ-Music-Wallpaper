package com.ragav.lockscreenplayer

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ragav.lockscreenplayer.data.GradientAnchorPreset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ragav.lockscreenplayer.data.PlaybackRepository
import com.ragav.lockscreenplayer.data.PlaybackUiState
import com.ragav.lockscreenplayer.data.TextAlignmentOption
import com.ragav.lockscreenplayer.ui.theme.LockscreenPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class WallpaperApplyTarget {
    LOCK_SCREEN,
    HOME_SCREEN,
    BOTH
}

class MainActivity : ComponentActivity() {
    private var hasNotificationListenerAccess by mutableStateOf(false)
    private var statusMessage by mutableStateOf(
        "Enable media access, then start your player and set HERTZ as your wallpaper."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
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
                    onApplyWallpaperChoice = ::applyWallpaperChoice,
                    onOpenSourceApp = ::openSourceApp,
                    onOpenSupportEmail = ::openSupportEmail,
                    onSetCardOffset = PlaybackRepository::setCardOffset,
                    onSetCardScale = PlaybackRepository::setCardScale,
                    onSetPlayerCardWidthScale = PlaybackRepository::setPlayerCardWidthScale,
                    onSetPlayerCardOffsetY = PlaybackRepository::setPlayerCardOffsetY,
                    onSetCardCornerRadius = PlaybackRepository::setCardCornerRadius,
                    onSetPlayerCardFrost = PlaybackRepository::setPlayerCardFrost,
                    onSetCardPauseHoldMs = PlaybackRepository::setCardPauseHoldMs,
                    onSetTitleTextScale = PlaybackRepository::setTitleTextScale,
                    onSetArtistTextScale = PlaybackRepository::setArtistTextScale,
                    onSetBlurAmount = PlaybackRepository::setBlurAmount,
                    onSetGradientBrightness = PlaybackRepository::setGradientBrightness,
                    onSetGradientAnchorPreset = PlaybackRepository::setGradientAnchorPreset,
                    onSetGradientAnchor = PlaybackRepository::setGradientAnchor,
                    onSetPreserveArtworkOnReboot = PlaybackRepository::setPreserveArtworkOnReboot,
                    onSetLockscreenMarqueeEnabled = PlaybackRepository::setLockscreenMarqueeEnabled,
                    onSetTextAlignment = PlaybackRepository::setTextAlignment,
                    onResetLayout = PlaybackRepository::resetLayout
                )
            }
        }
    }

    private fun configureSystemBars() {
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val decorView = window.decorView
            decorView.post {
                decorView.windowInsetsController?.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    override fun onResume() {
        super.onResume()
        syncPermissionState()
        PlaybackRepository.refreshBatteryState()
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

    private fun applyWallpaperChoice(showCard: Boolean, target: WallpaperApplyTarget) {
        when (target) {
            WallpaperApplyTarget.LOCK_SCREEN -> {
                PlaybackRepository.setShowCardOnLockScreen(showCard)
            }
            WallpaperApplyTarget.HOME_SCREEN -> {
                PlaybackRepository.setShowCardOnHomeScreen(showCard)
            }
            WallpaperApplyTarget.BOTH -> {
                PlaybackRepository.setShowCardOnLockScreen(showCard)
                PlaybackRepository.setShowCardOnHomeScreen(showCard)
            }
        }
        enableLiveWallpaper()
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
            data = Uri.parse("mailto:hertzmusic26@gmail.com")
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
    onApplyWallpaperChoice: (Boolean, WallpaperApplyTarget) -> Unit,
    onOpenSourceApp: (String) -> Unit,
    onOpenSupportEmail: () -> Unit,
    onSetCardOffset: (Float, Float) -> Unit,
    onSetCardScale: (Float) -> Unit,
    onSetPlayerCardWidthScale: (Float) -> Unit,
    onSetPlayerCardOffsetY: (Float) -> Unit,
    onSetCardCornerRadius: (Float) -> Unit,
    onSetPlayerCardFrost: (Float) -> Unit,
    onSetCardPauseHoldMs: (Long) -> Unit,
    onSetTitleTextScale: (Float) -> Unit,
    onSetArtistTextScale: (Float) -> Unit,
    onSetBlurAmount: (Float) -> Unit,
    onSetGradientBrightness: (Float) -> Unit,
    onSetGradientAnchorPreset: (GradientAnchorPreset) -> Unit,
    onSetGradientAnchor: (Int, Float, Float) -> Unit,
    onSetPreserveArtworkOnReboot: (Boolean) -> Unit,
    onSetLockscreenMarqueeEnabled: (Boolean) -> Unit,
    onSetTextAlignment: (TextAlignmentOption) -> Unit,
    onResetLayout: () -> Unit
) {
    var showApplyChooser by androidx.compose.runtime.remember { mutableStateOf(false) }

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
                subtitle = "Your Lock Screen Never Sounded Better."
            )

            StatusCard(statusMessage = statusMessage)

            if (playbackState.cardsDisabledForBattery) {
                InfoCard(
                    title = "Battery protection",
                    subtitle = "Battery is ${playbackState.batteryPercent}%. HERTZ keeps the lightweight gradient active, but hides music cards at 20% or below to reduce heat and battery drain."
                ) {
                    Text(
                        text = "Cards will return automatically when your battery goes above 20%.",
                        color = Color(0xFFE8E8F0),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

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
                        onClick = { showApplyChooser = true },
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
                        ButtonLabel("Apply wallpaper")
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
                        ButtonLabel(if (hasNotificationListenerAccess) "Manage media access" else "Enable media access")
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
                        ButtonLabel("App settings")
                    }
                }
            }

            CurrentPlayingCard(
                playbackState = playbackState,
                onOpenSourceApp = onOpenSourceApp
            )

            InfoCard(
                title = "Card visibility",
                subtitle = "Choose how long the artwork and text cards stay visible after pausing."
            ) {
                Text(
                    text = "Card stay after pause",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(10.dp))
                PauseHoldSelector(
                    selectedDurationMs = playbackState.cardPauseHoldMs,
                    onDurationSelected = onSetCardPauseHoldMs
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = pauseHoldDescription(playbackState.cardPauseHoldMs),
                    color = Color(0xFFD2D1DB),
                    style = MaterialTheme.typography.bodySmall
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
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp)
                )
                AppSlider(
                    value = playbackState.cardScale,
                    onValueChange = onSetCardScale,
                    valueRange = 0.34f..0.88f
                )

                Text(
                    text = "Text card width ${(playbackState.playerCardWidthScale * 100).toInt()}%",
                    color = Color(0xFFE8E8F0),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp)
                )
                AppSlider(
                    value = playbackState.playerCardWidthScale,
                    onValueChange = onSetPlayerCardWidthScale,
                    valueRange = 0.56f..0.96f
                )

                Text(
                    text = "Text card Y ${(playbackState.playerCardOffsetY * 100).toInt()}%",
                    color = Color(0xFFE8E8F0),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp)
                )
                AppSlider(
                    value = playbackState.playerCardOffsetY,
                    onValueChange = onSetPlayerCardOffsetY,
                    valueRange = -1.0f..2.4f
                )

                Text(
                    text = "Card radius ${(playbackState.cardCornerRadius * 100).toInt()}%",
                    color = Color(0xFFE8E8F0),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp)
                )
                AppSlider(
                    value = playbackState.cardCornerRadius,
                    onValueChange = onSetCardCornerRadius,
                    valueRange = 0f..0.30f
                )

                Text(
                    text = "Glass frost ${(playbackState.playerCardFrost * 100).toInt()}%",
                    color = Color(0xFFE8E8F0),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp)
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
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp)
                )
                AppSlider(
                    value = playbackState.blurAmount,
                    onValueChange = onSetBlurAmount,
                    valueRange = 0f..1f
                )

                Text(
                    text = "Gradient brightness ${(playbackState.gradientBrightness * 100).toInt()}%",
                    color = Color(0xFFE8E8F0),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp)
                )
                AppSlider(
                    value = playbackState.gradientBrightness,
                    onValueChange = onSetGradientBrightness,
                    valueRange = 0.65f..1.65f
                )

                Text(
                    text = "Gradient color picking",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Choose where HERTZ samples colors from the cover art.",
                    color = Color(0xFFD2D1DB),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                GradientAnchorPicker(
                    playbackState = playbackState,
                    onPresetSelected = onSetGradientAnchorPreset,
                    onAnchorChanged = onSetGradientAnchor
                )

                Text(
                    text = "Preserve album art for the next reboot",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                BooleanChoiceRow(
                    enabled = playbackState.preserveArtworkOnReboot,
                    enabledLabel = "On",
                    disabledLabel = "Off",
                    onEnabledChange = onSetPreserveArtworkOnReboot
                )

                Text(
                    text = "Lockscreen marquee",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                BooleanChoiceRow(
                    enabled = playbackState.lockscreenMarqueeEnabled,
                    enabledLabel = "On",
                    disabledLabel = "Off",
                    onEnabledChange = onSetLockscreenMarqueeEnabled
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
                    ButtonLabel("Reset layout")
                }
            }

            WallpaperPreviewCard(playbackState = playbackState)

            InfoCard(
                title = "Live wallpaper",
                subtitle = "Use apply wallpaper whenever you want to choose a card or no-card version for lock screen, home screen, or both."
            ) {
                Button(
                    onClick = { showApplyChooser = true },
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
                        ButtonLabel("Apply wallpaper")
                    }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Battery note: the music cards automatically hide at 20% battery or below. Motion only runs while the wallpaper is visible and music is actively playing; otherwise it settles into a static frame.",
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
                    ButtonLabel("Email support")
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "This opens your email app with the receiver set to hertzmusic26@gmail.com.",
                    color = Color(0xFFD2D1DB),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (showApplyChooser) {
            ApplyWallpaperDialog(
                playbackState = playbackState,
                onDismiss = { showApplyChooser = false },
                onApply = { showCard, target ->
                    showApplyChooser = false
                    onApplyWallpaperChoice(showCard, target)
                }
            )
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
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp)
    )
    AppSlider(
        value = xValue,
        onValueChange = { onValueChange(it, yValue) },
        valueRange = xRange
    )

    Text(
        text = "$yLabel ${(yValue * 100).toInt()}%",
        color = Color(0xFFE8E8F0),
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp)
    )
    AppSlider(
        value = yValue,
        onValueChange = { onValueChange(xValue, it) },
        valueRange = yRange
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
        thumb = { SliderHandle() },
        track = { sliderState -> SliderTrack(sliderState) },
        colors = SliderDefaults.colors(
            thumbColor = Color.Transparent,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderTrack(sliderState: SliderState) {
    val fraction = (
        (sliderState.value - sliderState.valueRange.start) /
            (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
        ).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.22f))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(16.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White)
        )
    }
}

@Composable
private fun SliderHandle() {
    Box(
        modifier = Modifier.size(width = 28.dp, height = 42.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(42.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White)
        )
    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CurrentPlayingCard(
    playbackState: PlaybackUiState,
    onOpenSourceApp: (String) -> Unit
) {
    val timelineText by rememberPlaybackPositionText(playbackState)
    val titleNeedsMarquee = playbackState.title.trim().length >= 16
    val artistNeedsMarquee = playbackState.artist.trim().length >= 20

    InfoCard(
        title = "Current playing",
        subtitle = if (playbackState.hasSourceSession) {
            "Colors are pulled from the current cover art."
        } else {
            "Start playing your music and the artwork will appear here."
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
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playbackState.title.uppercase(),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 13.sp * playbackState.titleTextScale
                            ),
                            textAlign = if (titleNeedsMarquee) {
                                TextAlign.Left
                            } else {
                                currentPlayingTextAlign(playbackState.textAlignment)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (titleNeedsMarquee) {
                                        Modifier.currentPlayingMarquee()
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                    if (playbackState.isExplicit) {
                        ExplicitTagBadge()
                    }
                }
                Text(
                    text = playbackState.artist,
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Normal,
                        fontSize = 11.sp * playbackState.artistTextScale
                    ),
                    textAlign = if (artistNeedsMarquee) {
                        TextAlign.Left
                    } else {
                        currentPlayingTextAlign(playbackState.textAlignment)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (artistNeedsMarquee) {
                                Modifier.currentPlayingMarquee()
                            } else {
                                Modifier
                            }
                        )
                )
                Text(
                    text = timelineText,
                    color = Color(0xFFE7E5F4),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    textAlign = TextAlign.Left,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = { onOpenSourceApp(playbackState.sourcePackage) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF111111)
                    )
                ) {
                    ButtonLabel(
                        text = "Open music application",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
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
            ButtonLabel(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
        ) {
            ButtonLabel(label)
        }
    }
}

@Composable
private fun GradientAnchorPicker(
    playbackState: PlaybackUiState,
    onPresetSelected: (GradientAnchorPreset) -> Unit,
    onAnchorChanged: (Int, Float, Float) -> Unit
) {
    val context = LocalContext.current
    val artwork = remember(playbackState.artworkBitmap) {
        playbackState.artworkBitmap ?: loadFallbackArtwork(context)
    }
    val previewArtwork = remember(artwork) {
        squareCropForAnchorPreview(artwork)
    }
    val anchorColors = remember(
        previewArtwork,
        playbackState.gradientAnchor1X,
        playbackState.gradientAnchor1Y,
        playbackState.gradientAnchor2X,
        playbackState.gradientAnchor2Y,
        playbackState.gradientAnchor3X,
        playbackState.gradientAnchor3Y
    ) {
        listOf(
            sampleAnchorPreviewColor(previewArtwork, playbackState.gradientAnchor1X, playbackState.gradientAnchor1Y),
            sampleAnchorPreviewColor(previewArtwork, playbackState.gradientAnchor2X, playbackState.gradientAnchor2Y),
            sampleAnchorPreviewColor(previewArtwork, playbackState.gradientAnchor3X, playbackState.gradientAnchor3Y)
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GradientAnchorPresetSelector(
            selectedPreset = playbackState.gradientAnchorPreset,
            onPresetSelected = onPresetSelected
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF070708))
        ) {
            val density = LocalDensity.current
            val boxSizePx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
            val dotSize = 22.dp
            val dotSizePx = with(density) { dotSize.toPx() }

            Image(
                bitmap = previewArtwork.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x33000000))
            )

            GradientAnchorDot(
                index = 1,
                x = playbackState.gradientAnchor1X,
                y = playbackState.gradientAnchor1Y,
                boxSizePx = boxSizePx,
                dotSize = dotSize,
                dotSizePx = dotSizePx,
                onAnchorChanged = onAnchorChanged
            )
            GradientAnchorDot(
                index = 2,
                x = playbackState.gradientAnchor2X,
                y = playbackState.gradientAnchor2Y,
                boxSizePx = boxSizePx,
                dotSize = dotSize,
                dotSizePx = dotSizePx,
                onAnchorChanged = onAnchorChanged
            )
            GradientAnchorDot(
                index = 3,
                x = playbackState.gradientAnchor3X,
                y = playbackState.gradientAnchor3Y,
                boxSizePx = boxSizePx,
                dotSize = dotSize,
                dotSizePx = dotSizePx,
                onAnchorChanged = onAnchorChanged
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            anchorColors.forEachIndexed { index, color ->
                AnchorColorSwatch(
                    label = "Point ${index + 1}",
                    color = Color(color),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun GradientAnchorDot(
    index: Int,
    x: Float,
    y: Float,
    boxSizePx: Float,
    dotSize: androidx.compose.ui.unit.Dp,
    dotSizePx: Float,
    onAnchorChanged: (Int, Float, Float) -> Unit
) {
    val latestX by rememberUpdatedState(x)
    val latestY by rememberUpdatedState(y)
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (x.coerceIn(0f, 1f) * boxSizePx - dotSizePx / 2f).roundToInt(),
                    y = (y.coerceIn(0f, 1f) * boxSizePx - dotSizePx / 2f).roundToInt()
                )
            }
            .size(dotSize)
            .shadow(8.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(Color(0xFFFF1D1D))
            .border(1.dp, Color(0x99FFFFFF), CircleShape)
            .pointerInput(index, boxSizePx) {
                var dragX = latestX
                var dragY = latestY
                detectDragGestures(
                    onDragStart = {
                        dragX = latestX
                        dragY = latestY
                    }
                ) { change, dragAmount ->
                    change.consume()
                    dragX = (dragX + dragAmount.x / boxSizePx).coerceIn(0f, 1f)
                    dragY = (dragY + dragAmount.y / boxSizePx).coerceIn(0f, 1f)
                    onAnchorChanged(index, dragX, dragY)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = index.toString(),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 11.sp),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AnchorColorSwatch(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x22FFFFFF))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color)
                .border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(5.dp))
        )
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun GradientAnchorPresetSelector(
    selectedPreset: GradientAnchorPreset,
    onPresetSelected: (GradientAnchorPreset) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(percent = 3),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ButtonLabel(
                    text = selectedPreset.label,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(20.dp),
                    tint = Color(0xFFD8D6E4)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 3))
                .background(Color(0xF0101012))
        ) {
            GradientAnchorPreset.selectablePresets.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = preset.label,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp)
                        )
                    },
                    onClick = {
                        expanded = false
                        onPresetSelected(preset)
                    }
                )
            }
        }
    }
}

@Composable
private fun PauseHoldSelector(
    selectedDurationMs: Long,
    onDurationSelected: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(percent = 3),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ButtonLabel(
                    text = pauseHoldMenuLabel(selectedDurationMs),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(20.dp),
                    tint = Color(0xFFD8D6E4)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 3))
                .background(Color(0xF0101012))
        ) {
            PlaybackRepository.CARD_PAUSE_HOLD_OPTIONS_MS.forEach { durationMs ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = pauseHoldMenuLabel(durationMs),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp)
                        )
                    },
                    onClick = {
                        expanded = false
                        onDurationSelected(durationMs)
                    }
                )
            }
        }
    }
}

private fun pauseHoldLabel(durationMs: Long): String {
    return when (durationMs) {
        0L -> "Now"
        5_000L -> "5s"
        10_000L -> "10s"
        20_000L -> "20s"
        30_000L -> "30s"
        60_000L -> "1m"
        300_000L -> "5m"
        600_000L -> "10m"
        else -> "${durationMs / 1000L}s"
    }
}

private fun pauseHoldMenuLabel(durationMs: Long): String {
    return if (durationMs <= 0L) {
        "Immediately"
    } else {
        "${pauseHoldLabel(durationMs)} after pause"
    }
}

private fun pauseHoldDescription(durationMs: Long): String {
    return if (durationMs <= 0L) {
        "Cards hide immediately when playback pauses or the player closes."
    } else {
        "After pausing, cards stay for ${pauseHoldLabel(durationMs)} and then disappear without a fade."
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.currentPlayingMarquee(): Modifier {
    return basicMarquee(
        iterations = Int.MAX_VALUE,
        animationMode = MarqueeAnimationMode.Immediately,
        initialDelayMillis = 0,
        velocity = 46.dp
    )
}

@Composable
private fun ExplicitTagBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.92f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "E",
            color = Color.Black.copy(alpha = 0.58f),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 10.sp)
        )
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
            ButtonLabel(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
        ) {
            ButtonLabel(label)
        }
    }
}

@Composable
private fun WallpaperPreviewCard(playbackState: PlaybackUiState) {
    val context = LocalContext.current
    val previewBitmap by produceState<Bitmap?>(
        initialValue = null,
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
        playbackState.gradientBrightness,
        playbackState.gradientAnchorPreset,
        playbackState.gradientAnchor1X,
        playbackState.gradientAnchor1Y,
        playbackState.gradientAnchor2X,
        playbackState.gradientAnchor2Y,
        playbackState.gradientAnchor3X,
        playbackState.gradientAnchor3Y,
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
        playbackState.showCardOnHomeScreen,
        playbackState.cardsDisabledForBattery
    ) {
        value = withContext(Dispatchers.Default) {
            LiveWallpaperRenderer.render(
                context = context,
                state = playbackState,
                width = 540,
                height = 1170,
                phase = if (playbackState.isPlaying) playbackState.fluidity * 4f else 0f,
                drawCards = !playbackState.cardsDisabledForBattery &&
                    (playbackState.showCardOnLockScreen || playbackState.showCardOnHomeScreen)
            )
        }
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
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
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

@Composable
private fun BooleanChoiceRow(
    enabled: Boolean,
    enabledLabel: String,
    disabledLabel: String,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (enabled) {
            Button(
                onClick = { onEnabledChange(true) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF111111)
                )
            ) {
                ButtonLabel(enabledLabel)
            }
        } else {
            OutlinedButton(
                onClick = { onEnabledChange(true) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
            ) {
                ButtonLabel(enabledLabel)
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
                ButtonLabel(disabledLabel)
            }
        } else {
            OutlinedButton(
                onClick = { onEnabledChange(false) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
            ) {
                ButtonLabel(disabledLabel)
            }
        }
    }
}

@Composable
private fun ApplyWallpaperDialog(
    playbackState: PlaybackUiState,
    onDismiss: () -> Unit,
    onApply: (Boolean, WallpaperApplyTarget) -> Unit
) {
    var showCard by androidx.compose.runtime.remember { mutableStateOf(true) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xF0101012))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Apply wallpaper",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Pick whether this applied version should include the music card or keep only the fluid gradient background.",
                    color = Color(0xFFD2D1DB),
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WallpaperModeCard(
                        modifier = Modifier.weight(1f),
                        playbackState = playbackState,
                        showCard = true,
                        selected = showCard,
                        title = "With card",
                        subtitle = "Gradient plus the artwork and text card.",
                        onClick = { showCard = true }
                    )
                    WallpaperModeCard(
                        modifier = Modifier.weight(1f),
                        playbackState = playbackState,
                        showCard = false,
                        selected = !showCard,
                        title = "No card",
                        subtitle = "Gradient only for a cleaner home screen.",
                        onClick = { showCard = false }
                    )
                }

                Text(
                    text = "Apply this version to",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )

                Button(
                    onClick = { onApply(showCard, WallpaperApplyTarget.LOCK_SCREEN) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF111111)
                    )
                ) {
                    ButtonLabel("Lock screen")
                }

                Button(
                    onClick = { onApply(showCard, WallpaperApplyTarget.HOME_SCREEN) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF111111)
                    )
                ) {
                    ButtonLabel("Home screen")
                }

                Button(
                    onClick = { onApply(showCard, WallpaperApplyTarget.BOTH) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF111111)
                    )
                ) {
                    ButtonLabel("Both")
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8D6E4))
                ) {
                    ButtonLabel("Cancel")
                }
            }
        }
    }
}

@Composable
private fun WallpaperModeCard(
    modifier: Modifier = Modifier,
    playbackState: PlaybackUiState,
    showCard: Boolean,
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val previewBitmap by produceState<Bitmap?>(
        initialValue = null,
        playbackState.trackSignature,
        playbackState.artworkBitmap,
        playbackState.cardOffsetX,
        playbackState.cardOffsetY,
        playbackState.cardScale,
        playbackState.cardCornerRadius,
        playbackState.playerCardWidthScale,
        playbackState.playerCardOffsetY,
        playbackState.playerCardFrost,
        playbackState.titleTextScale,
        playbackState.artistTextScale,
        playbackState.blurAmount,
        playbackState.gradientBrightness,
        playbackState.gradientAnchorPreset,
        playbackState.gradientAnchor1X,
        playbackState.gradientAnchor1Y,
        playbackState.gradientAnchor2X,
        playbackState.gradientAnchor2Y,
        playbackState.gradientAnchor3X,
        playbackState.gradientAnchor3Y,
        playbackState.fluidity,
        showCard
    ) {
        value = withContext(Dispatchers.Default) {
            LiveWallpaperRenderer.render(
                context = context,
                state = playbackState,
                width = 240,
                height = 520,
                phase = if (playbackState.isPlaying) playbackState.fluidity * 3f else 0f,
                drawCards = showCard
            )
        }
    }

    val borderColor = if (selected) Color.White else Color(0x44FFFFFF)
    val containerColor = if (selected) Color(0x26FFFFFF) else Color(0x16000000)

    Card(
        modifier = modifier
            .border(1.dp, borderColor, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.46f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF08080A))
            ) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = subtitle,
                color = Color(0xFFD2D1DB),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ButtonLabel(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start
) {
    Text(
        text = text,
        modifier = modifier,
        textAlign = textAlign,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 18.sp
        )
    )
}

private fun displayPlaybackPosition(playbackState: PlaybackUiState): String {
    val effectiveDuration = playbackState.durationMs.coerceAtLeast(0L)
    val elapsed = if (playbackState.isPlaying) {
        ((SystemClock.elapsedRealtime() - playbackState.positionCapturedAtMs).coerceAtLeast(0L) * playbackState.playbackSpeed).toLong()
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

private fun currentPlayingTextAlign(alignment: TextAlignmentOption): TextAlign {
    return when (alignment) {
        TextAlignmentOption.LEFT -> TextAlign.Left
        TextAlignmentOption.CENTER -> TextAlign.Center
        TextAlignmentOption.RIGHT -> TextAlign.Right
    }
}

private fun squareCropForAnchorPreview(source: Bitmap): Bitmap {
    if (source.width <= 0 || source.height <= 0) return source
    val side = minOf(source.width, source.height).coerceAtLeast(1)
    if (source.width == side && source.height == side) return source
    val left = ((source.width - side) / 2).coerceAtLeast(0)
    val top = ((source.height - side) / 2).coerceAtLeast(0)
    return runCatching {
        Bitmap.createBitmap(source, left, top, side, side)
    }.getOrElse {
        source
    }
}

private fun sampleAnchorPreviewColor(bitmap: Bitmap, xRatio: Float, yRatio: Float): Int {
    if (bitmap.width <= 0 || bitmap.height <= 0) return AndroidColor.WHITE
    val centerX = ((bitmap.width - 1) * xRatio.coerceIn(0f, 1f)).roundToInt().coerceIn(0, bitmap.width - 1)
    val centerY = ((bitmap.height - 1) * yRatio.coerceIn(0f, 1f)).roundToInt().coerceIn(0, bitmap.height - 1)
    val radius = maxOf(1, minOf(bitmap.width, bitmap.height) / 42)
    var red = 0
    var green = 0
    var blue = 0
    var count = 0

    for (x in (centerX - radius).coerceAtLeast(0)..(centerX + radius).coerceAtMost(bitmap.width - 1)) {
        for (y in (centerY - radius).coerceAtLeast(0)..(centerY + radius).coerceAtMost(bitmap.height - 1)) {
            val pixel = bitmap.getPixel(x, y)
            red += AndroidColor.red(pixel)
            green += AndroidColor.green(pixel)
            blue += AndroidColor.blue(pixel)
            count += 1
        }
    }

    val safeCount = count.coerceAtLeast(1)
    return AndroidColor.argb(
        255,
        (red / safeCount).coerceIn(0, 255),
        (green / safeCount).coerceIn(0, 255),
        (blue / safeCount).coerceIn(0, 255)
    )
}

@Composable
private fun rememberPlaybackPositionText(playbackState: PlaybackUiState) = produceState(
    initialValue = displayPlaybackPosition(playbackState),
    playbackState.trackSignature,
    playbackState.isPlaying,
    playbackState.playbackSpeed,
    playbackState.positionMs,
    playbackState.positionCapturedAtMs,
    playbackState.durationMs
) {
    while (true) {
        value = displayPlaybackPosition(playbackState)
        if (!playbackState.isPlaying) break
        delay(1_000L)
    }
}

private fun displayTime(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0:00"
    val totalSeconds = milliseconds / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
