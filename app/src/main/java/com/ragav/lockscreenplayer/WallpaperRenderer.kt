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
import android.os.Build
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

object LiveWallpaperRenderer {
    fun render(
        context: Context,
        state: PlaybackUiState,
        width: Int,
        height: Int,
        phase: Float = 0f
    ): Bitmap {
        val effectiveFluidity = max(state.fluidity, 0.62f)
        val effectiveFluidScale = max(state.fluidScale, 0.82f)
        val shouldShowCard = PlaybackRepository.shouldShowCard(state)
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val sourceArtwork = state.artworkBitmap ?: loadFallbackArtwork(context)
        val artworkForPalette = scaleAndCrop(sourceArtwork, max(240, safeWidth / 3), max(240, safeHeight / 3))
        val output = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val palette = extractPalette(artworkForPalette)
        val fluidLayer = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
        val fluidCanvas = Canvas(fluidLayer)
        val layerBlurRadiusPx = (64 + state.blurAmount * 180).toInt()

        drawPaletteBase(canvas, palette, safeWidth, safeHeight)
        drawDiffuseBackdrop(
            canvas = fluidCanvas,
            palette = palette,
            width = safeWidth,
            height = safeHeight,
            phase = phase,
            fluidity = effectiveFluidity,
            blurAmount = state.blurAmount,
            fluidScale = effectiveFluidScale
        )
        val softenedFluid = createBlurredBitmap(fluidLayer, layerBlurRadiusPx)
        val fluidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (170 + effectiveFluidity * 40f + state.blurAmount * 30f).toInt().coerceIn(130, 238)
            isFilterBitmap = true
        }
        canvas.drawBitmap(softenedFluid, 0f, 0f, fluidPaint)
        drawShade(canvas, safeWidth, safeHeight)

        if (shouldShowCard) {
            val coverRect = drawArtworkCard(canvas, sourceArtwork, state, safeWidth, safeHeight)
            drawPlayerCard(canvas, state, safeWidth, safeHeight, coverRect, context, phase, softenedFluid)
        }

        return output
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
                width.toFloat(),
                height.toFloat(),
                intArrayOf(
                    withAlpha(palette[0], 255),
                    withAlpha(palette[1], 245),
                    withAlpha(palette[2], 235)
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)
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
        val scale = 0.78f + fluidScale * 1.04f
        val motion = 0.08f + fluidity * 0.22f
        val bloomAlpha = (150 + fluidity * 60f + blurAmount * 24f).toInt().coerceIn(110, 225)
        val blobs = listOf(
            BlobSpec(
                cx = width * (0.18f + motion * sin(phase * 0.42f + 0.2f)),
                cy = height * (0.18f + motion * cos(phase * 0.38f + 1.2f)),
                radius = min(width, height) * (0.34f + scale * 0.24f + blurAmount * 0.06f),
                color = palette[0]
            ),
            BlobSpec(
                cx = width * (0.84f - motion * sin(phase * 0.36f + 0.8f)),
                cy = height * (0.24f + motion * sin(phase * 0.54f + 2.0f)),
                radius = min(width, height) * (0.32f + scale * 0.22f + blurAmount * 0.06f),
                color = palette[1]
            ),
            BlobSpec(
                cx = width * (0.48f + motion * cos(phase * 0.46f + 2.1f)),
                cy = height * (0.80f - motion * sin(phase * 0.58f + 1.0f)),
                radius = min(width, height) * (0.30f + scale * 0.20f + blurAmount * 0.05f),
                color = palette[2]
            ),
            BlobSpec(
                cx = width * (0.16f + motion * cos(phase * 0.31f + 2.6f)),
                cy = height * (0.70f + motion * sin(phase * 0.34f + 0.5f)),
                radius = min(width, height) * (0.26f + scale * 0.16f + blurAmount * 0.05f),
                color = blendColors(palette[0], palette[2], 0.45f)
            ),
            BlobSpec(
                cx = width * (0.78f - motion * cos(phase * 0.28f + 1.8f)),
                cy = height * (0.66f - motion * sin(phase * 0.32f + 1.4f)),
                radius = min(width, height) * (0.28f + scale * 0.17f + blurAmount * 0.05f),
                color = blendColors(palette[1], palette[0], 0.42f)
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
            canvas.drawCircle(blob.cx, blob.cy, blob.radius, blobPaint)
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
                    AndroidColor.argb(20, 255, 255, 255),
                    AndroidColor.argb(32, 255, 255, 255),
                    AndroidColor.argb(102, 8, 6, 10),
                    AndroidColor.argb(150, 4, 3, 6)
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
        val cardSize = min(width * state.cardScale, width * 0.88f)
        val left = (width - cardSize) / 2f + width * 0.24f * state.cardOffsetX
        val top = height * 0.12f + height * 0.18f * state.cardOffsetY
        val rect = RectF(left, top, left + cardSize, top + cardSize)
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

    private fun drawPlayerCard(
        canvas: Canvas,
        state: PlaybackUiState,
        width: Int,
        height: Int,
        coverRect: RectF,
        context: Context,
        phase: Float,
        backdropBitmap: Bitmap
    ) {
        val panelWidth = (width * state.playerCardWidthScale)
            .coerceIn(width * 0.56f, width * 0.96f)
        val panelHeight = height * 0.094f
        val panelLeft = ((width - panelWidth) / 2f + width * 0.22f * state.cardOffsetX)
            .coerceIn(width * 0.04f, width - panelWidth - width * 0.04f)
        val defaultTop = coverRect.bottom + height * 0.028f
        val panelTop = defaultTop.coerceAtMost(height - panelHeight - height * 0.05f)
        val rect = RectF(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight)
        val radius = min(panelHeight * (0.20f + state.cardCornerRadius * 0.7f), 58f)
        val calSans = ResourcesCompat.getFont(context, R.font.calsans_regular)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val outfit = ResourcesCompat.getFont(context, R.font.outfit_variable)
            ?: Typeface.create("sans-serif", Typeface.NORMAL)
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
            color = AndroidColor.argb(255, 16, 16, 16)
            textAlign = align
            textSize = width * 0.032f * state.titleTextScale
            typeface = calSans
        }
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb((255 * 0.7f).toInt(), 255, 255, 255)
            textAlign = align
            textSize = width * 0.024f * state.artistTextScale
            typeface = weightedTypeface(outfit, 600)
        }

        drawAnimatedText(
            canvas = canvas,
            text = state.title,
            paint = titlePaint,
            left = contentLeft,
            right = contentRight,
            baselineY = titleY,
            phase = phase,
            isPlaying = state.isPlaying,
            maxCharactersBeforeMarquee = 29
        )
        drawAnimatedText(
            canvas = canvas,
            text = state.artist,
            paint = artistPaint,
            left = contentLeft,
            right = contentRight,
            baselineY = artistY,
            phase = phase + 1.3f,
            isPlaying = state.isPlaying,
            maxCharactersBeforeMarquee = 29
        )

        drawTimeline(canvas, rect, contentLeft, contentRight, timelineY, state)
    }

    private fun drawTimeline(
        canvas: Canvas,
        rect: RectF,
        left: Float,
        right: Float,
        centerY: Float,
        state: PlaybackUiState
    ) {
        val effectiveDuration = state.durationMs.coerceAtLeast(1L)
        val elapsed = if (state.isPlaying) {
            (SystemClock.elapsedRealtime() - state.positionCapturedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val currentPosition = (state.positionMs + elapsed).coerceAtMost(effectiveDuration)
        val progress = (currentPosition.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(88, 0, 0, 0)
            strokeWidth = rect.height() * 0.04f
            strokeCap = Paint.Cap.ROUND
        }
        val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(210, 0, 0, 0)
            strokeWidth = rect.height() * 0.04f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(left, centerY, right, centerY, trackPaint)
        canvas.drawLine(left, centerY, left + (right - left) * progress, centerY, progressPaint)

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(180, 30, 30, 30)
            textSize = rect.height() * 0.11f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        canvas.drawText(formatTime(currentPosition), left, centerY - rect.height() * 0.07f, timePaint)
        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("-${formatTime((effectiveDuration - currentPosition).coerceAtLeast(0L))}", right, centerY - rect.height() * 0.07f, timePaint)
    }

}

private fun drawAnimatedText(
    canvas: Canvas,
    text: String,
    paint: Paint,
    left: Float,
    right: Float,
    baselineY: Float,
    phase: Float,
    isPlaying: Boolean,
    maxCharactersBeforeMarquee: Int
) {
    val availableWidth = (right - left).coerceAtLeast(1f)
    val trimmed = text.trim()
    if (trimmed.isBlank()) return

    val shouldMarquee = trimmed.length > maxCharactersBeforeMarquee &&
        paint.measureText(trimmed) > availableWidth

    if (!shouldMarquee) {
        val anchorX = when (paint.textAlign) {
            Paint.Align.LEFT -> left
            Paint.Align.CENTER -> (left + right) / 2f
            Paint.Align.RIGHT -> right
        }
        canvas.drawText(fitText(trimmed, paint, availableWidth), anchorX, baselineY, paint)
        return
    }

    val originalAlign = paint.textAlign
    paint.textAlign = Paint.Align.LEFT
    val spacing = paint.textSize * 1.8f
    val textWidth = paint.measureText(trimmed)
    val loopWidth = textWidth + spacing
    val scroll = if (isPlaying) ((phase * paint.textSize * 1.65f) % loopWidth) else 0f

    canvas.save()
    canvas.clipRect(left, baselineY - paint.textSize * 1.3f, right, baselineY + paint.textSize * 0.45f)
    var drawX = left - scroll
    while (drawX < right) {
        canvas.drawText(trimmed, drawX, baselineY, paint)
        drawX += loopWidth
    }
    canvas.restore()
    paint.textAlign = originalAlign
}

private data class BlobSpec(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val color: Int
)

fun createBlurredBitmap(source: Bitmap, blurRadiusPx: Int): Bitmap {
    val safeRadius = blurRadiusPx.coerceAtLeast(24)
    val tinyDivisor = (10 + safeRadius / 8).coerceAtLeast(14)
    val smallDivisor = (6 + safeRadius / 18).coerceAtLeast(8)
    val tinyWidth = max(1, source.width / tinyDivisor)
    val tinyHeight = max(1, source.height / tinyDivisor)
    val smallWidth = max(1, source.width / smallDivisor)
    val smallHeight = max(1, source.height / smallDivisor)

    val tiny = Bitmap.createScaledBitmap(source, tinyWidth, tinyHeight, true)
    val softened = Bitmap.createScaledBitmap(tiny, smallWidth, smallHeight, true)
    val medium = Bitmap.createScaledBitmap(softened, max(1, source.width / 2), max(1, source.height / 2), true)
    val full = Bitmap.createScaledBitmap(medium, source.width, source.height, true)
    val finalPass = Bitmap.createScaledBitmap(full, max(1, source.width / 2), max(1, source.height / 2), true)
    return Bitmap.createScaledBitmap(finalPass, source.width, source.height, true)
}

fun scaleAndCrop(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
    val scale = max(targetWidth / source.width.toFloat(), targetHeight / source.height.toFloat())
    val scaledWidth = max(1, (source.width * scale).toInt())
    val scaledHeight = max(1, (source.height * scale).toInt())
    val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
    val x = max(0, (scaledWidth - targetWidth) / 2)
    val y = max(0, (scaledHeight - targetHeight) / 2)
    return Bitmap.createBitmap(
        scaled,
        x,
        y,
        min(targetWidth, scaled.width - x),
        min(targetHeight, scaled.height - y)
    )
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

private fun extractPalette(bitmap: Bitmap): List<Int> {
    val points = listOf(
        bitmap.width * 0.22f to bitmap.height * 0.28f,
        bitmap.width * 0.76f to bitmap.height * 0.24f,
        bitmap.width * 0.56f to bitmap.height * 0.72f
    )
    return points.map { (x, y) ->
        sampledAverageColor(bitmap, x.toInt(), y.toInt(), max(8, min(bitmap.width, bitmap.height) / 16))
    }
}

private fun sampledAverageColor(bitmap: Bitmap, centerX: Int, centerY: Int, radius: Int): Int {
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0L

    val startX = max(0, centerX - radius)
    val endX = min(bitmap.width - 1, centerX + radius)
    val startY = max(0, centerY - radius)
    val endY = min(bitmap.height - 1, centerY + radius)

    for (x in startX..endX step 2) {
        for (y in startY..endY step 2) {
            val color = bitmap.getPixel(x, y)
            red += AndroidColor.red(color)
            green += AndroidColor.green(color)
            blue += AndroidColor.blue(color)
            count++
        }
    }

    if (count == 0L) return AndroidColor.argb(255, 182, 126, 126)
    return AndroidColor.argb(255, (red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
}

private fun withAlpha(color: Int, alpha: Int): Int {
    return AndroidColor.argb(
        alpha.coerceIn(0, 255),
        AndroidColor.red(color),
        AndroidColor.green(color),
        AndroidColor.blue(color)
    )
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

private fun weightedTypeface(base: Typeface, weight: Int): Typeface {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Typeface.create(base, weight.coerceIn(100, 900), false)
    } else {
        Typeface.create(base, Typeface.BOLD)
    }
}
