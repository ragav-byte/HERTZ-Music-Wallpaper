package com.ragav.lockscreenplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.ragav.lockscreenplayer.data.PlaybackRepository
import com.ragav.lockscreenplayer.data.PlaybackUiState
import com.ragav.lockscreenplayer.data.TextAlignmentOption
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object LiveWallpaperRenderer {
    private data class BackgroundCacheKey(
        val width: Int,
        val height: Int,
        val trackSignature: String,
        val artworkSignature: String,
        val artworkGenerationId: Int,
        val blurBucket: Int,
        val brightnessBucket: Int,
        val anchor1XBucket: Int,
        val anchor1YBucket: Int,
        val anchor2XBucket: Int,
        val anchor2YBucket: Int,
        val anchor3XBucket: Int,
        val anchor3YBucket: Int
    )

    private data class BackgroundCache(
        val key: BackgroundCacheKey,
        val bitmap: Bitmap
    )

    @Volatile
    private var backgroundCache: BackgroundCache? = null

    fun render(
        context: Context,
        state: PlaybackUiState,
        width: Int,
        height: Int,
        phase: Float = 0f,
        drawCards: Boolean = true
    ): Bitmap {
        val gradientBrightness = state.gradientBrightness.coerceIn(0.65f, 1.65f)
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val sourceArtwork = state.artworkBitmap
            ?.takeIf { it.isUsableBitmap() }
            ?: loadFallbackArtwork(context)
        val backgroundKey = BackgroundCacheKey(
            width = safeWidth,
            height = safeHeight,
            trackSignature = state.trackSignature,
            artworkSignature = state.artworkSignature,
            artworkGenerationId = sourceArtwork.safeGenerationId(),
            blurBucket = (state.blurAmount * 100f).toInt(),
            brightnessBucket = (gradientBrightness * 100f).toInt(),
            anchor1XBucket = (state.gradientAnchor1X.coerceIn(0f, 1f) * 1000f).toInt(),
            anchor1YBucket = (state.gradientAnchor1Y.coerceIn(0f, 1f) * 1000f).toInt(),
            anchor2XBucket = (state.gradientAnchor2X.coerceIn(0f, 1f) * 1000f).toInt(),
            anchor2YBucket = (state.gradientAnchor2Y.coerceIn(0f, 1f) * 1000f).toInt(),
            anchor3XBucket = (state.gradientAnchor3X.coerceIn(0f, 1f) * 1000f).toInt(),
            anchor3YBucket = (state.gradientAnchor3Y.coerceIn(0f, 1f) * 1000f).toInt()
        )
        val background = backgroundCache
            ?.takeIf { it.key == backgroundKey }
            ?.bitmap
            ?: createBackground(
                sourceArtwork = sourceArtwork,
                width = safeWidth,
                height = safeHeight,
                anchors = gradientAnchorsForState(state),
                gradientBrightness = gradientBrightness,
                blurAmount = state.blurAmount
            ).also { generated ->
                backgroundCache = BackgroundCache(backgroundKey, generated)
            }
        if (!drawCards) {
            return background
        }

        val output = background.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        val coverRect = if (state.artworkBitmap?.isUsableBitmap() == true) {
            drawArtworkCard(canvas, state.artworkBitmap, state, safeWidth, safeHeight)
        } else {
            artworkCardRect(state, safeWidth, safeHeight)
        }
        drawPlayerCard(canvas, state, safeWidth, safeHeight, coverRect, context, background)

        return output
    }

    private fun createBackground(
        sourceArtwork: Bitmap,
        width: Int,
        height: Int,
        anchors: List<PaletteAnchor>,
        gradientBrightness: Float,
        blurAmount: Float
    ): Bitmap {
        val artworkForPalette = scaleForPalette(squareCropForPalette(sourceArtwork))
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val basePalette = extractPalette(artworkForPalette, anchors)
        val accentPalette = basePalette.map { enrichColorPresence(it, gradientBrightness) }

        drawPaletteBase(canvas, accentPalette, width, height)
        drawStaticGradientGlow(canvas, accentPalette, width, height, blurAmount)
        drawShade(canvas, width, height)
        return output
    }

    private fun gradientAnchorsForState(state: PlaybackUiState): List<PaletteAnchor> {
        return listOf(
            PaletteAnchor(
                xRatio = state.gradientAnchor1X.coerceIn(0f, 1f),
                yRatio = state.gradientAnchor1Y.coerceIn(0f, 1f),
                radiusRatio = 0.18f
            ),
            PaletteAnchor(
                xRatio = state.gradientAnchor2X.coerceIn(0f, 1f),
                yRatio = state.gradientAnchor2Y.coerceIn(0f, 1f),
                radiusRatio = 0.20f
            ),
            PaletteAnchor(
                xRatio = state.gradientAnchor3X.coerceIn(0f, 1f),
                yRatio = state.gradientAnchor3Y.coerceIn(0f, 1f),
                radiusRatio = 0.18f
            )
        )
    }

    private fun drawPaletteBase(
        canvas: Canvas,
        palette: List<Int>,
        width: Int,
        height: Int
    ) {
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                intArrayOf(
                    preserveColorForWallpaper(palette[0], 0.04f),
                    preserveColorForWallpaper(blendColors(palette[1], palette[0], 0.10f), 0.03f),
                    preserveColorForWallpaper(blendColors(palette[2], palette[3], 0.12f), 0.06f),
                    preserveColorForWallpaper(palette[3], 0.10f)
                ),
                floatArrayOf(0f, 0.32f, 0.68f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)
    }

    private fun drawStaticGradientGlow(
        canvas: Canvas,
        palette: List<Int>,
        width: Int,
        height: Int,
        blurAmount: Float
    ) {
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val alpha = (168 + blurAmount * 46f).toInt().coerceIn(150, 222)
        val glows = listOf(
            BlobSpec(
                cx = width * 0.16f,
                cy = height * 0.20f,
                radius = min(width, height) * 0.76f,
                color = palette[0],
                stretchX = 1.25f,
                stretchY = 0.90f
            ),
            BlobSpec(
                cx = width * 0.86f,
                cy = height * 0.34f,
                radius = min(width, height) * 0.72f,
                color = palette[1],
                stretchX = 1.08f,
                stretchY = 1.02f
            ),
            BlobSpec(
                cx = width * 0.52f,
                cy = height * 0.84f,
                radius = min(width, height) * 0.82f,
                color = palette[2],
                stretchX = 1.40f,
                stretchY = 0.84f
            ),
            BlobSpec(
                cx = width * 0.60f,
                cy = height * 0.50f,
                radius = min(width, height) * 0.64f,
                color = blendColors(palette[3], palette[0], 0.18f),
                stretchX = 1.18f,
                stretchY = 1.10f
            )
        )

        glows.forEach { glow ->
            glowPaint.shader = RadialGradient(
                glow.cx,
                glow.cy,
                glow.radius,
                intArrayOf(withAlpha(glow.color, alpha), withAlpha(glow.color, 0)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.scale(glow.stretchX, glow.stretchY, glow.cx, glow.cy)
            canvas.drawCircle(glow.cx, glow.cy, glow.radius, glowPaint)
            canvas.restore()
        }
    }

    private fun drawDiffuseBackdrop(
        canvas: Canvas,
        palette: List<Int>,
        width: Int,
        height: Int,
        phase: Float,
        fluidity: Float,
        blurAmount: Float,
        fluidScale: Float
    ) {
        val scale = 0.96f + fluidScale * 1.18f
        val motion = 0.065f + fluidity * 0.14f
        val bloomAlpha = (172 + fluidity * 52f + blurAmount * 18f).toInt().coerceIn(140, 238)
        val blobs = listOf(
            BlobSpec(
                cx = width * (0.20f + motion * sin(phase * 0.20f + 0.6f)),
                cy = height * (0.18f + motion * cos(phase * 0.18f + 1.1f)),
                radius = min(width, height) * (0.42f + scale * 0.24f + blurAmount * 0.06f),
                color = blendColors(palette[0], palette[1], 0.16f),
                stretchX = 1.26f,
                stretchY = 0.82f
            ),
            BlobSpec(
                cx = width * (0.82f - motion * sin(phase * 0.24f + 1.8f)),
                cy = height * (0.24f + motion * sin(phase * 0.22f + 2.1f)),
                radius = min(width, height) * (0.40f + scale * 0.22f + blurAmount * 0.06f),
                color = blendColors(palette[1], palette[2], 0.12f),
                stretchX = 1.22f,
                stretchY = 0.86f
            ),
            BlobSpec(
                cx = width * (0.52f + motion * cos(phase * 0.26f + 2.0f)),
                cy = height * (0.80f - motion * sin(phase * 0.28f + 0.9f)),
                radius = min(width, height) * (0.38f + scale * 0.20f + blurAmount * 0.05f),
                color = blendColors(palette[2], palette[0], 0.18f),
                stretchX = 1.08f,
                stretchY = 1.06f
            ),
            BlobSpec(
                cx = width * (0.08f + motion * cos(phase * 0.18f + 2.5f)),
                cy = height * (0.60f + motion * sin(phase * 0.20f + 0.4f)),
                radius = min(width, height) * (0.34f + scale * 0.16f + blurAmount * 0.04f),
                color = blendColors(palette[0], palette[2], 0.42f),
                stretchX = 1.36f,
                stretchY = 0.74f
            ),
            BlobSpec(
                cx = width * (0.94f - motion * cos(phase * 0.16f + 1.6f)),
                cy = height * (0.68f - motion * sin(phase * 0.18f + 1.2f)),
                radius = min(width, height) * (0.34f + scale * 0.17f + blurAmount * 0.04f),
                color = blendColors(palette[1], palette[0], 0.38f),
                stretchX = 1.34f,
                stretchY = 0.76f
            ),
            BlobSpec(
                cx = width * (0.50f + motion * sin(phase * 0.14f + 0.1f)),
                cy = height * (0.46f + motion * cos(phase * 0.16f + 2.8f)),
                radius = min(width, height) * (0.44f + scale * 0.22f + blurAmount * 0.05f),
                color = blendColors(palette[0], palette[1], 0.5f),
                stretchX = 1.55f,
                stretchY = 0.62f
            )
        )

        val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        blobs.forEach { blob ->
            blobPaint.shader = RadialGradient(
                blob.cx,
                blob.cy,
                blob.radius,
                intArrayOf(withAlpha(blob.color, bloomAlpha), withAlpha(blob.color, 0)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.scale(blob.stretchX, blob.stretchY, blob.cx, blob.cy)
            canvas.drawCircle(blob.cx, blob.cy, blob.radius, blobPaint)
            canvas.restore()
        }
    }

    private fun drawShade(canvas: Canvas, width: Int, height: Int) {
        val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                intArrayOf(
                    AndroidColor.argb(10, 255, 255, 255),
                    AndroidColor.argb(18, 255, 255, 255),
                    AndroidColor.argb(58, 8, 6, 10),
                    AndroidColor.argb(104, 4, 3, 6)
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
    }

    private fun drawArtworkCard(
        canvas: Canvas,
        sourceArtwork: Bitmap,
        state: PlaybackUiState,
        width: Int,
        height: Int
    ): RectF {
        val rect = artworkCardRect(state, width, height)
        val cardSize = rect.width()
        val radius = min(cardSize * state.cardCornerRadius, 78f)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(78, 0, 0, 0)
            setShadowLayer(44f, 0f, 26f, AndroidColor.argb(110, 0, 0, 0))
        }
        canvas.drawRoundRect(rect, radius, radius, shadowPaint)

        val cardBitmap = scaleAndCrop(sourceArtwork, rect.width().toInt(), rect.height().toInt())
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        val clipPath = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(cardBitmap, null, rect, cardPaint)
        canvas.restore()
        return rect
    }

    private fun artworkCardRect(
        state: PlaybackUiState,
        width: Int,
        height: Int
    ): RectF {
        val cardSize = min(width * state.cardScale, width * 0.88f)
        val left = (width - cardSize) / 2f + width * 0.24f * state.cardOffsetX
        val top = height * 0.12f + height * 0.18f * state.cardOffsetY
        return RectF(left, top, left + cardSize, top + cardSize)
    }

    private fun drawPlayerCard(
        canvas: Canvas,
        state: PlaybackUiState,
        width: Int,
        height: Int,
        coverRect: RectF,
        context: Context,
        backdropBitmap: Bitmap
    ) {
        val panelWidth = (width * state.playerCardWidthScale)
            .coerceIn(width * 0.56f, width * 0.96f)
        val panelHeight = height * 0.094f
        val panelLeft = ((width - panelWidth) / 2f + width * 0.22f * state.cardOffsetX)
            .coerceIn(width * 0.04f, width - panelWidth - width * 0.04f)
        val defaultTop = coverRect.bottom + height * 0.028f
        val panelTop = (defaultTop + height * 0.10f * state.playerCardOffsetY)
            .coerceIn(height * 0.04f, height - panelHeight - height * 0.05f)
        val rect = RectF(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight)
        val radius = min(panelHeight * (0.20f + state.cardCornerRadius * 0.7f), 58f)
        val calSans = ResourcesCompat.getFont(context, R.font.calsans_regular)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val clipPath = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
        val sourceRect = Rect(
            rect.left.toInt().coerceIn(0, backdropBitmap.width - 1),
            rect.top.toInt().coerceIn(0, backdropBitmap.height - 1),
            rect.right.toInt().coerceIn(1, backdropBitmap.width),
            rect.bottom.toInt().coerceIn(1, backdropBitmap.height)
        )
        val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (86 + state.playerCardFrost * 90f).toInt().coerceIn(60, 176)
            isFilterBitmap = true
        }
        val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(
                (44 + state.playerCardFrost * 116f).toInt().coerceIn(36, 184),
                255,
                255,
                255
            )
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, rect.height() * 0.018f)
            color = AndroidColor.argb(
                (42 + state.playerCardFrost * 90f).toInt().coerceIn(32, 132),
                255,
                255,
                255
            )
        }

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(backdropBitmap, sourceRect, rect, backdropPaint)
        canvas.drawRoundRect(rect, radius, radius, frostPaint)
        canvas.restore()
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val textAreaShiftX = rect.width() * 0.10f * state.textOffsetX
        val textAreaShiftY = rect.height() * 0.10f * state.textOffsetY
        val contentLeft = rect.left + rect.width() * 0.08f + textAreaShiftX
        val contentRight = rect.right - rect.width() * 0.08f + textAreaShiftX
        val titleY = rect.top + rect.height() * 0.30f + textAreaShiftY
        val artistY = rect.top + rect.height() * 0.54f + textAreaShiftY
        val timelineY = rect.top + rect.height() * 0.80f + textAreaShiftY
        val align = state.textAlignment.toPaintAlign()

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(255, 255, 255, 255)
            textAlign = align
            textSize = width * 0.032f * state.titleTextScale
            typeface = calSans
        }
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb((255 * 0.65f).toInt(), 255, 255, 255)
            textAlign = align
            textSize = width * 0.024f * state.artistTextScale
            typeface = calSans
        }

        drawTitleText(
            canvas = canvas,
            text = state.title,
            isExplicit = state.isExplicit,
            paint = titlePaint,
            left = contentLeft,
            right = contentRight,
            baselineY = titleY,
            alignment = state.textAlignment,
            typeface = calSans
        )
        drawSmartText(
            canvas = canvas,
            text = state.artist,
            paint = artistPaint,
            left = contentLeft,
            right = contentRight,
            baselineY = artistY,
            alignment = state.textAlignment
        )

        drawTimeline(canvas, rect, contentLeft, contentRight, timelineY, state, calSans)
    }

    private fun drawTimeline(
        canvas: Canvas,
        rect: RectF,
        left: Float,
        right: Float,
        centerY: Float,
        state: PlaybackUiState,
        calSans: Typeface
    ) {
        val effectiveDuration = state.durationMs.coerceAtLeast(1L)
        val elapsed = if (state.isPlaying) {
            ((SystemClock.elapsedRealtime() - state.positionCapturedAtMs).coerceAtLeast(0L) * state.playbackSpeed).toLong()
        } else {
            0L
        }
        val currentPosition = (state.positionMs + elapsed).coerceAtMost(effectiveDuration)
        val progress = (currentPosition.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(120, 255, 255, 255)
            strokeWidth = rect.height() * 0.04f
            strokeCap = Paint.Cap.ROUND
        }
        val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(235, 255, 255, 255)
            strokeWidth = rect.height() * 0.04f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(left, centerY, right, centerY, trackPaint)
        canvas.drawLine(left, centerY, left + (right - left) * progress, centerY, progressPaint)

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(230, 255, 255, 255)
            textSize = rect.height() * 0.11f + 2f
            typeface = calSans
        }
        canvas.drawText(formatTime(currentPosition), left, centerY - rect.height() * 0.07f, timePaint)
        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatTime(effectiveDuration), right, centerY - rect.height() * 0.07f, timePaint)
    }

}

private fun drawTitleText(
    canvas: Canvas,
    text: String,
    isExplicit: Boolean,
    paint: Paint,
    left: Float,
    right: Float,
    baselineY: Float,
    alignment: TextAlignmentOption,
    typeface: Typeface
) {
    if (!isExplicit) {
        drawSmartText(
            canvas = canvas,
            text = text,
            paint = paint,
            left = left,
            right = right,
            baselineY = baselineY,
            alignment = alignment
        )
        return
    }

    val availableWidth = (right - left).coerceAtLeast(1f)
    val trimmed = text.trim()
    if (trimmed.isBlank()) return

    val badgeSize = paint.textSize * 0.78f
    val badgeGap = paint.textSize * 0.26f
    val badgeWidth = badgeSize
    val fullTextWidth = paint.measureText(trimmed)

    val originalAlign = paint.textAlign
    paint.textAlign = Paint.Align.LEFT

    val forceLeftCrop = trimmed.length >= 29
    val textX = if (forceLeftCrop) {
        left
    } else {
        val groupWidth = (fullTextWidth + badgeGap + badgeWidth).coerceAtMost(availableWidth)
        when (alignment) {
            TextAlignmentOption.LEFT -> left
            TextAlignmentOption.CENTER -> left + (availableWidth - groupWidth) / 2f
            TextAlignmentOption.RIGHT -> right - groupWidth
        }.coerceIn(left, right)
    }
    canvas.save()
    canvas.clipRect(left, baselineY - paint.textSize * 1.3f, right, baselineY + paint.textSize * 0.45f)
    canvas.drawText(trimmed, textX, baselineY, paint)
    if (!forceLeftCrop && fullTextWidth + badgeGap + badgeWidth <= availableWidth) {
        drawExplicitBadge(
            canvas = canvas,
            centerX = textX + fullTextWidth + badgeGap + badgeWidth / 2f,
            centerY = baselineY - paint.textSize * 0.34f,
            size = badgeSize,
            typeface = typeface
        )
    }
    canvas.restore()
    paint.textAlign = originalAlign
}

private fun drawExplicitBadge(
    canvas: Canvas,
    centerX: Float,
    centerY: Float,
    size: Float,
    typeface: Typeface
) {
    val rect = RectF(
        centerX - size * 0.50f,
        centerY - size * 0.45f,
        centerX + size * 0.50f,
        centerY + size * 0.45f
    )
    val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(52, 255, 255, 255)
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(238, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = size * 0.70f
        this.typeface = typeface
    }
    canvas.drawRoundRect(rect, size * 0.18f, size * 0.18f, badgePaint)
    val textCenter = centerY - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText("E", centerX, textCenter, textPaint)
}

private fun drawSmartText(
    canvas: Canvas,
    text: String,
    paint: Paint,
    left: Float,
    right: Float,
    baselineY: Float,
    alignment: TextAlignmentOption,
    rightPadding: Float = 0f
) {
    val availableWidth = (right - left - rightPadding).coerceAtLeast(1f)
    val trimmed = text.trim()
    if (trimmed.isBlank()) return

    val originalAlign = paint.textAlign
    paint.textAlign = Paint.Align.LEFT
    val forceLeftCrop = trimmed.length >= 29
    val textWidth = paint.measureText(trimmed)
    val drawX = if (forceLeftCrop) {
        left
    } else {
        when (alignment) {
            TextAlignmentOption.LEFT -> left
            TextAlignmentOption.CENTER -> left + (availableWidth - textWidth.coerceAtMost(availableWidth)) / 2f
            TextAlignmentOption.RIGHT -> left + availableWidth - textWidth.coerceAtMost(availableWidth)
        }
    }
    canvas.save()
    canvas.clipRect(left, baselineY - paint.textSize * 1.3f, left + availableWidth, baselineY + paint.textSize * 0.45f)
    canvas.drawText(trimmed, drawX, baselineY, paint)
    canvas.restore()
    paint.textAlign = originalAlign
}

private data class BlobSpec(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val color: Int,
    val stretchX: Float = 1f,
    val stretchY: Float = 1f
)

private data class PaletteBucket(
    val color: Int,
    val coverage: Float
)

private data class PaletteAnchor(
    val xRatio: Float,
    val yRatio: Float,
    val radiusRatio: Float
)

fun scaleAndCrop(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
    val safeTargetWidth = targetWidth.coerceAtLeast(1)
    val safeTargetHeight = targetHeight.coerceAtLeast(1)
    return runCatching {
        require(source.isUsableBitmap()) { "Invalid source bitmap" }
        val scale = max(safeTargetWidth / source.width.toFloat(), safeTargetHeight / source.height.toFloat())
        val scaledWidth = max(1, (source.width * scale).toInt())
        val scaledHeight = max(1, (source.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val x = max(0, (scaledWidth - safeTargetWidth) / 2)
        val y = max(0, (scaledHeight - safeTargetHeight) / 2)
        Bitmap.createBitmap(
            scaled,
            x,
            y,
            min(safeTargetWidth, scaled.width - x).coerceAtLeast(1),
            min(safeTargetHeight, scaled.height - y).coerceAtLeast(1)
        )
    }.getOrElse {
        solidFallbackBitmap(safeTargetWidth, safeTargetHeight)
    }
}

fun scaleForPalette(source: Bitmap): Bitmap {
    if (!source.isUsableBitmap()) return solidFallbackBitmap(1, 1)
    val largestEdge = max(source.width, source.height).coerceAtLeast(1)
    val targetEdge = 96
    if (largestEdge <= targetEdge) return source
    val scale = targetEdge.toFloat() / largestEdge.toFloat()
    val targetWidth = max(1, (source.width * scale).toInt())
    val targetHeight = max(1, (source.height * scale).toInt())
    return runCatching {
        Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }.getOrElse {
        solidFallbackBitmap(1, 1)
    }
}

private fun squareCropForPalette(source: Bitmap): Bitmap {
    if (!source.isUsableBitmap()) return solidFallbackBitmap(1, 1)
    val side = min(source.width, source.height).coerceAtLeast(1)
    if (source.width == side && source.height == side) return source
    val left = ((source.width - side) / 2).coerceAtLeast(0)
    val top = ((source.height - side) / 2).coerceAtLeast(0)
    return runCatching {
        Bitmap.createBitmap(source, left, top, side, side)
    }.getOrElse {
        source
    }
}

fun fitText(text: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    var candidate = text
    while (candidate.length > 1 && paint.measureText("$candidate...") > maxWidth) {
        candidate = candidate.dropLast(1)
    }
    return "$candidate..."
}

fun loadFallbackArtwork(context: Context): Bitmap {
    val drawable = checkNotNull(ContextCompat.getDrawable(context, R.drawable.album_art_placeholder)) {
        "Album art drawable missing"
    }
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

private fun extractPalette(bitmap: Bitmap, anchors: List<PaletteAnchor>): List<Int> {
    if (!bitmap.isUsableBitmap()) return fallbackPalette()
    val sampleWidth = min(72, bitmap.width.coerceAtLeast(1))
    val sampleHeight = min(72, bitmap.height.coerceAtLeast(1))
    val sampledBitmap = runCatching {
        Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
    }.getOrElse {
        return fallbackPalette()
    }
    val selectedPalette = mutableListOf<Int>()

    anchors.forEach { anchor ->
        dominantColorForAnchor(sampledBitmap, anchor)?.let { color ->
            selectedPalette += color
        }
    }

    if (selectedPalette.size >= 3) {
        if (selectedPalette.take(3).all(::isNearBlackNeutral)) {
            return neutralDarkPalette()
        }
        val bridge = blendColors(
            blendColors(selectedPalette[0], selectedPalette[1], 0.50f),
            selectedPalette[2],
            0.34f
        )
        selectedPalette += bridge
    }

    while (selectedPalette.size < 4) {
        val last = selectedPalette.lastOrNull() ?: AndroidColor.argb(255, 182, 126, 126)
        selectedPalette += if (selectedPalette.isEmpty()) {
            last
        } else {
            blendColors(selectedPalette.first(), last, 0.5f)
        }
    }

    return selectedPalette.take(4)
}

private fun fallbackPalette(): List<Int> {
    return listOf(
        AndroidColor.argb(255, 182, 126, 126),
        AndroidColor.argb(255, 110, 83, 120),
        AndroidColor.argb(255, 69, 86, 118),
        AndroidColor.argb(255, 38, 34, 46)
    )
}

private fun neutralDarkPalette(): List<Int> {
    return listOf(
        AndroidColor.rgb(16, 16, 16),
        AndroidColor.rgb(23, 23, 23),
        AndroidColor.rgb(34, 34, 34),
        AndroidColor.rgb(43, 43, 43)
    )
}

private fun dominantColorForAnchor(
    bitmap: Bitmap,
    anchor: PaletteAnchor
): Int? {
    val centerX = ((bitmap.width - 1).coerceAtLeast(0) * anchor.xRatio).toInt().coerceIn(0, bitmap.width - 1)
    val centerY = ((bitmap.height - 1).coerceAtLeast(0) * anchor.yRatio).toInt().coerceIn(0, bitmap.height - 1)
    val radiusX = max(1, (bitmap.width * anchor.radiusRatio * 0.20f).toInt())
    val radiusY = max(1, (bitmap.height * anchor.radiusRatio * 0.20f).toInt())
    val startX = (centerX - radiusX).coerceAtLeast(0)
    val endX = (centerX + radiusX).coerceAtMost(bitmap.width - 1)
    val startY = (centerY - radiusY).coerceAtLeast(0)
    val endY = (centerY + radiusY).coerceAtMost(bitmap.height - 1)
    val accumulator = BucketAccumulator()

    for (x in startX..endX) {
        for (y in startY..endY) {
            val normalizedX = (x - centerX).toFloat() / radiusX.toFloat()
            val normalizedY = (y - centerY).toFloat() / radiusY.toFloat()
            if (normalizedX * normalizedX + normalizedY * normalizedY > 1f) continue
            val pixel = bitmap.getPixel(x, y)
            val distance = sqrt(normalizedX * normalizedX + normalizedY * normalizedY).coerceIn(0f, 1f)
            val weight = 1f + (1f - distance) * 9f
            accumulator.add(pixel, weight)
        }
    }

    return accumulator.averageColor()
}

private fun nextDistinctGlobalColor(globalPalette: List<Int>, selectedColors: List<Int>): Int? {
    return globalPalette
        .filterNot { candidate ->
            selectedColors.any { selected -> colorDistance(candidate, selected) < 32f }
        }
        .maxByOrNull { candidate ->
            paletteInterest(candidate) * 0.82f +
                colorUsability(candidate) * 0.55f +
                minColorDistance(candidate, selectedColors) / 460f
        }
}

private fun extractGlobalPalette(sampledBitmap: Bitmap): List<Int> {
    val buckets = LinkedHashMap<Int, BucketAccumulator>()

    for (x in 0 until sampledBitmap.width) {
        for (y in 0 until sampledBitmap.height) {
            val pixel = sampledBitmap.getPixel(x, y)
            val bucketKey = quantizedColorKey(pixel)
            val bucket = buckets.getOrPut(bucketKey) { BucketAccumulator() }
            bucket.add(pixel)
        }
    }

    val totalPixels = (sampledBitmap.width * sampledBitmap.height).coerceAtLeast(1)
    val rankedBuckets = buckets.values
        .map { accumulator ->
            PaletteBucket(
                color = accumulator.averageColor(),
                coverage = accumulator.count.toFloat() / totalPixels.toFloat()
            )
        }
        .sortedByDescending { bucket ->
            bucket.coverage * (1.5f + paletteInterest(bucket.color) * 0.32f)
        }

    val selected = mutableListOf<PaletteBucket>()
    rankedBuckets.firstOrNull()?.let(selected::add)

    while (selected.size < 4 && rankedBuckets.isNotEmpty()) {
        val next = rankedBuckets
            .filterNot { candidate -> selected.any { it.color == candidate.color } }
            .maxByOrNull { candidate ->
                candidate.coverage * 3.2f +
                    paletteInterest(candidate.color) * 0.18f +
                    minColorDistance(candidate.color, selected.map { it.color }) / 420f
            }
            ?: break
        selected += next
    }

    while (selected.size < 4) {
        val last = selected.lastOrNull()?.color ?: AndroidColor.argb(255, 182, 126, 126)
        val fallbackBlend = if (selected.isEmpty()) {
            last
        } else {
            blendColors(selected.first().color, last, 0.5f)
        }
        selected += PaletteBucket(fallbackBlend, 0f)
    }

    return selected.take(4).map { it.color }
}

private fun paletteInterest(color: Int): Float {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color, hsv)
    val saturation = hsv[1]
    val value = hsv[2]
    return saturation * 1.22f + value * 0.26f
}

private fun colorUsability(color: Int): Float {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color, hsv)
    val saturation = hsv[1]
    val value = hsv[2]
    val darkPenalty = when {
        value < 0.08f -> -0.70f
        value < 0.16f -> -0.34f
        else -> 0f
    }
    val washedOutPenalty = if (saturation < 0.08f && value > 0.86f) -0.24f else 0f
    return saturation * 0.46f + value * 0.24f + darkPenalty + washedOutPenalty
}

private fun isNearBlackNeutral(color: Int): Boolean {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color, hsv)
    return hsv[2] <= 0.16f && hsv[1] <= 0.18f
}

private fun minColorDistance(color: Int, selected: List<Int>): Float {
    if (selected.isEmpty()) return 255f
    return selected.minOf { selectedColor ->
        colorDistance(color, selectedColor)
    }
}

private fun colorDistance(first: Int, second: Int): Float {
    val redDiff = AndroidColor.red(first) - AndroidColor.red(second)
    val greenDiff = AndroidColor.green(first) - AndroidColor.green(second)
    val blueDiff = AndroidColor.blue(first) - AndroidColor.blue(second)
    return sqrt((redDiff * redDiff + greenDiff * greenDiff + blueDiff * blueDiff).toDouble()).toFloat()
}

private fun withAlpha(color: Int, alpha: Int): Int {
    return AndroidColor.argb(
        alpha.coerceIn(0, 255),
        AndroidColor.red(color),
        AndroidColor.green(color),
        AndroidColor.blue(color)
    )
}

private fun darkenColor(color: Int, amount: Float): Int {
    val safeAmount = amount.coerceIn(0f, 1f)
    return AndroidColor.argb(
        AndroidColor.alpha(color),
        (AndroidColor.red(color) * (1f - safeAmount)).toInt().coerceIn(0, 255),
        (AndroidColor.green(color) * (1f - safeAmount)).toInt().coerceIn(0, 255),
        (AndroidColor.blue(color) * (1f - safeAmount)).toInt().coerceIn(0, 255)
    )
}

private fun preserveColorForWallpaper(color: Int, darkenAmount: Float): Int {
    val safeAmount = darkenAmount.coerceIn(0f, 0.22f)
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color, hsv)
    if (isNearBlackNeutral(color)) {
        hsv[1] = 0f
        hsv[2] = (hsv[2] * (1f - safeAmount)).coerceIn(0.06f, 1f)
    } else {
        hsv[1] = (hsv[1] * 1.12f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * (1f - safeAmount)).coerceIn(0.22f, 1f)
    }
    return AndroidColor.HSVToColor(AndroidColor.alpha(color), hsv)
}

private fun enrichColorPresence(color: Int, factor: Float): Int {
    val normalizedFactor = ((factor - 1f) / 0.65f).coerceIn(-1f, 1f)
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color, hsv)
    if (isNearBlackNeutral(color)) {
        hsv[1] = 0f
        hsv[2] = (hsv[2] * (1f + max(0f, normalizedFactor) * 0.12f)).coerceIn(0.06f, 0.32f)
    } else {
        hsv[1] = (hsv[1] * (1f + normalizedFactor * 0.48f) + max(0f, normalizedFactor) * 0.08f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * (1f + normalizedFactor * 0.10f)).coerceIn(0.18f, 1f)
    }
    return AndroidColor.HSVToColor(AndroidColor.alpha(color), hsv)
}

private fun blendColors(startColor: Int, endColor: Int, ratio: Float): Int {
    val safeRatio = ratio.coerceIn(0f, 1f)
    val inverse = 1f - safeRatio
    return AndroidColor.argb(
        255,
        (AndroidColor.red(startColor) * inverse + AndroidColor.red(endColor) * safeRatio).toInt(),
        (AndroidColor.green(startColor) * inverse + AndroidColor.green(endColor) * safeRatio).toInt(),
        (AndroidColor.blue(startColor) * inverse + AndroidColor.blue(endColor) * safeRatio).toInt()
    )
}

private fun quantizedColorKey(color: Int): Int {
    val red = AndroidColor.red(color) / 22
    val green = AndroidColor.green(color) / 22
    val blue = AndroidColor.blue(color) / 22
    return (red shl 16) or (green shl 8) or blue
}

private fun Bitmap.isUsableBitmap(): Boolean {
    return !isRecycled && width > 0 && height > 0
}

private fun Bitmap.safeGenerationId(): Int {
    return runCatching { generationId }.getOrDefault(0)
}

private fun solidFallbackBitmap(width: Int, height: Int): Bitmap {
    return Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply {
        eraseColor(AndroidColor.argb(255, 38, 34, 46))
    }
}

private class BucketAccumulator {
    var redSum = 0f
    var greenSum = 0f
    var blueSum = 0f
    var count = 0
    var weight = 0f

    fun add(color: Int, sampleWeight: Float = 1f) {
        val safeWeight = sampleWeight.coerceAtLeast(0.01f)
        redSum += AndroidColor.red(color) * safeWeight
        greenSum += AndroidColor.green(color) * safeWeight
        blueSum += AndroidColor.blue(color) * safeWeight
        count += 1
        weight += safeWeight
    }

    fun averageColor(): Int {
        if (count == 0 || weight <= 0f) {
            return AndroidColor.argb(255, 182, 126, 126)
        }
        return AndroidColor.argb(
            255,
            (redSum / weight).toInt().coerceIn(0, 255),
            (greenSum / weight).toInt().coerceIn(0, 255),
            (blueSum / weight).toInt().coerceIn(0, 255)
        )
    }
}

private fun playerTextAnchorX(left: Float, right: Float, alignment: TextAlignmentOption): Float {
    return when (alignment) {
        TextAlignmentOption.LEFT -> left
        TextAlignmentOption.CENTER -> (left + right) / 2f
        TextAlignmentOption.RIGHT -> right
    }
}

private fun TextAlignmentOption.toPaintAlign(): Paint.Align {
    return when (this) {
        TextAlignmentOption.LEFT -> Paint.Align.LEFT
        TextAlignmentOption.CENTER -> Paint.Align.CENTER
        TextAlignmentOption.RIGHT -> Paint.Align.RIGHT
    }
}

private fun formatTime(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0:00"
    val totalSeconds = milliseconds / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
