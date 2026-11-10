package com.example.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.example.model.AudioSpectrumTimeline
import com.example.model.CameraMovementPreset
import com.example.model.LyricLine
import com.example.model.ProjectEdl
import com.example.model.StoryboardCue
import com.example.model.VideoModelType
import com.example.model.VisualFilterStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared frame renderer used by both the interactive Video Preview and the real HD MP4 encoder.
 * Executes the approved [ProjectEdl] (or fallback StoryboardCues), including scene selection,
 * per-scene camera movements, boundary transitions, instrumental vs chorus lighting treatment,
 * user-corrected lyric overlays, and drives its 24-band equalizer from decoded PCM
 * [AudioSpectrumTimeline] frames at the mapped source audio timestamp.
 */
object VideoFrameRenderer {

    private val bitmapPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    private val overlayPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val transitionPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val barPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val particlePaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val pillPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val textPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    private val subTextPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    private val cueTagPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    private val transformMatrix by lazy { Matrix() }

    /**
     * Resolves the active [StoryboardCue] at normalized timeline position [progress] (0.0..1.0).
     */
    fun resolveActiveCue(cues: List<StoryboardCue>, progress: Float): StoryboardCue? {
        if (cues.isEmpty()) return null
        val clamped = progress.coerceIn(0f, 1f)
        return cues.firstOrNull { clamped >= it.startFraction && clamped <= it.endFraction }
            ?: cues.minByOrNull { abs(clamped - ((it.startFraction + it.endFraction) * 0.5f)) }
    }

    /**
     * Resolves the effective camera movement preset for the active scene cue, honoring
     * scene-specific camera overrides or camera notes in the AI storyboard.
     */
    fun resolveSceneCameraPreset(
        defaultCamera: CameraMovementPreset,
        activeCue: StoryboardCue?
    ): CameraMovementPreset {
        if (activeCue == null) return defaultCamera
        activeCue.cameraPresetOverride?.let { return it }
        val note = activeCue.cameraNote.lowercase()
        val section = activeCue.sectionType.uppercase()
        return when {
            "pump" in note || "bass" in note || "burst" in note || "CHORUS" in section ->
                CameraMovementPreset.BASS_PUMP_ZOOM
            "orbit" in note || "sway" in note || "pan" in note ->
                CameraMovementPreset.ORBITAL_SWAY
            "handheld" in note || "drift" in note || "parallax" in note ->
                CameraMovementPreset.PARALLAX_DRIFT
            "push" in note || "zoom" in note || "intro" in note ->
                CameraMovementPreset.SLOW_PUSH_IN
            else -> defaultCamera
        }
    }

    fun renderFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        sourceBitmap: Bitmap?,
        timeSec: Float,
        durationSec: Float,
        modelType: VideoModelType,
        cameraPreset: CameraMovementPreset,
        filterStyle: VisualFilterStyle,
        songTitle: String,
        promptText: String,
        lyricsEnabled: Boolean,
        lyrics: List<LyricLine>,
        storyboardCues: List<StoryboardCue> = emptyList(),
        spectrumTimeline: AudioSpectrumTimeline? = null,
        projectEdl: ProjectEdl? = null,
        assetFileResolver: ((String) -> java.io.File?)? = null
    ) {
        val w = width.toFloat()
        val h = height.toFloat()

        val resolvedEdl = projectEdl?.let {
            EdlTimelineEngine.resolveRenderStateAt(it, timeSec, cameraPreset)
        }
        val effectiveDuration = (resolvedEdl?.totalEditedDurationSec ?: durationSec).coerceAtLeast(1f)
        val effectiveEditedTime = resolvedEdl?.editedTimeSec ?: timeSec.coerceIn(0f, effectiveDuration)
        val sourceAudioTimeSec = resolvedEdl?.sourceAudioTimeSec ?: timeSec
        val progress = (effectiveEditedTime / effectiveDuration).coerceIn(0f, 1f)

        // 1. Resolve active Scene from ProjectEdl (preferred) or StoryboardCues
        val effectiveCues = if (projectEdl != null) {
            EdlTimelineEngine.toStoryboardCues(projectEdl)
        } else {
            storyboardCues
        }
        val activeCue = resolveActiveCue(effectiveCues, progress)
        val cueStart = activeCue?.startFraction ?: 0f
        val cueEnd = (activeCue?.endFraction ?: 1f).coerceAtLeast(cueStart + 0.05f)
        val sceneLocalProgress = resolvedEdl?.sceneLocalProgress
            ?: ((progress - cueStart) / (cueEnd - cueStart)).coerceIn(0f, 1f)

        val sectionUpper = activeCue?.sectionType?.uppercase().orEmpty()
        val isChorusSection = if (activeCue != null) {
            "CHORUS" in sectionUpper || "DROP" in sectionUpper || activeCue.lightingIntensity >= 0.85f
        } else {
            progress in 0.45f..0.80f
        }
        val isInstrumentalSection = "INSTRUMENTAL" in sectionUpper ||
            "INTRO" in sectionUpper ||
            "OUTRO" in sectionUpper ||
            activeCue?.vocalSectionTag?.contains("Instrumental", ignoreCase = true) == true

        val cueLighting = activeCue?.lightingIntensity ?: if (isChorusSection) 0.95f else 0.65f
        val effectiveCamera = resolvedEdl?.effectiveCamera ?: resolveSceneCameraPreset(cameraPreset, activeCue)

        // 2. Sample actual decoded PCM frequency spectrum at [sourceAudioTimeSec]
        val spectrum = spectrumTimeline?.spectrumAt(sourceAudioTimeSec) ?: FloatArray(24) { 0.05f }
        val bassEnergy = if (spectrum.size >= 4) {
            (spectrum[0] + spectrum[1] + spectrum[2] + spectrum[3]) / 4f
        } else {
            0.1f
        }

        // 3. Clear background
        canvas.drawColor(Color.parseColor("#0A0912"))

        // 4. Compute camera motion transform per active Scene & Transition boundary
        val boundaryDist = minOf(sceneLocalProgress, 1f - sceneLocalProgress)
        val isNearSceneBoundary = activeCue != null && boundaryDist < 0.08f
        val boundaryFactor = if (isNearSceneBoundary) (1f - (boundaryDist / 0.08f)).coerceIn(0f, 1f) else 0f
        val transitionType = activeCue?.visualTransition?.lowercase().orEmpty()

        val whipPanOffset = if ("whip" in transitionType || "pan" in transitionType) {
            boundaryFactor * w * 0.08f * (if (sceneLocalProgress < 0.5f) -1f else 1f)
        } else {
            0f
        }
        val zoomCutBoost = if ("zoom" in transitionType) boundaryFactor * 0.12f else 0f

        val baseScale = when (effectiveCamera) {
            CameraMovementPreset.SLOW_PUSH_IN ->
                1.04f + sceneLocalProgress * 0.20f + bassEnergy * 0.05f + zoomCutBoost
            CameraMovementPreset.ORBITAL_SWAY ->
                1.14f + sin(timeSec * 0.9f) * 0.04f + bassEnergy * 0.04f + zoomCutBoost
            CameraMovementPreset.BASS_PUMP_ZOOM -> {
                val pulseBoost = if (modelType == VideoModelType.BEAT_PULSE || isChorusSection) 0.18f else 0.11f
                1.06f + bassEnergy * pulseBoost + zoomCutBoost
            }
            CameraMovementPreset.PARALLAX_DRIFT ->
                1.12f + cos(timeSec * 0.7f) * 0.05f + bassEnergy * 0.04f + zoomCutBoost
        }

        val panX = whipPanOffset + when (effectiveCamera) {
            CameraMovementPreset.ORBITAL_SWAY -> sin(timeSec * 1.1f) * (w * 0.045f)
            CameraMovementPreset.PARALLAX_DRIFT -> cos(timeSec * 0.85f) * (w * 0.035f)
            else -> sin(timeSec * 0.5f) * (w * 0.015f)
        }
        val panY = when (effectiveCamera) {
            CameraMovementPreset.PARALLAX_DRIFT -> sin(timeSec * 1.3f) * (h * 0.03f)
            else -> cos(timeSec * 0.6f) * (h * 0.015f)
        }

        // 5. Hybrid Scene Rendering:
        //    If the active scene references a verified MP4 asset (`assetId`), resolve it at the
        //    rendering boundary via `SceneVideoFrameExtractor` and decode the frame at
        //    `sceneLocalProgress`; otherwise render the photo-animated `sourceBitmap`.
        val activeAssetId = resolvedEdl?.activeScene?.assetId ?: activeCue?.assetId
        val sceneVideoFrame = if (!activeAssetId.isNullOrBlank()) {
            SceneVideoFrameExtractor.getFrameForAssetId(
                assetId = activeAssetId,
                sceneLocalProgress = sceneLocalProgress,
                customResolver = assetFileResolver
            )
        } else {
            null
        }
        val bitmapToDraw = sceneVideoFrame ?: sourceBitmap

        if (bitmapToDraw != null && !bitmapToDraw.isRecycled) {
            val bw = bitmapToDraw.width.toFloat().coerceAtLeast(1f)
            val bh = bitmapToDraw.height.toFloat().coerceAtLeast(1f)
            // When rendering a real Veo video clip, keep subtle framing unless zoom transition is active
            val effectiveScale = if (sceneVideoFrame != null) {
                1.0f + zoomCutBoost + bassEnergy * 0.02f
            } else {
                baseScale
            }
            val coverScale = maxOf(w / bw, h / bh) * effectiveScale

            transformMatrix.reset()
            transformMatrix.postTranslate(-bw / 2f, -bh / 2f)
            if (sceneVideoFrame == null && effectiveCamera == CameraMovementPreset.ORBITAL_SWAY) {
                transformMatrix.postRotate(sin(timeSec * 0.8f) * 1.8f)
            }
            transformMatrix.postScale(coverScale, coverScale)
            val tx = if (sceneVideoFrame != null) w / 2f + whipPanOffset else w / 2f + panX
            val ty = if (sceneVideoFrame != null) h / 2f else h / 2f + panY
            transformMatrix.postTranslate(tx, ty)

            canvas.drawBitmap(bitmapToDraw, transformMatrix, bitmapPaint)
        }

        // 6. Atmospheric color grade & instrumental/chorus visual treatment
        val primaryColor = filterStyle.primaryHex.toInt()
        val secondaryColor = filterStyle.secondaryHex.toInt()

        val topAlpha = (60 + cueLighting * 75f).toInt().coerceIn(45, 165)
        val bottomAlpha = if (isInstrumentalSection) 235 else 210
        val topTint = Color.argb(
            topAlpha,
            Color.red(primaryColor),
            Color.green(primaryColor),
            Color.blue(primaryColor)
        )
        val bottomDark = Color.argb(bottomAlpha, 10, 9, 18)

        overlayPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(topTint, Color.argb(40, 18, 14, 36), bottomDark),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, overlayPaint)

        // Radial chorus bloom driven by Storyboard lightingIntensity and real PCM bass energy
        if (isChorusSection || modelType == VideoModelType.CINEMA_PRO || cueLighting >= 0.7f) {
            val glowRadius = maxOf(w, h) * (0.38f + bassEnergy * 0.30f * cueLighting)
            val glowAlpha = ((110f * cueLighting * (0.4f + bassEnergy * 0.6f))).toInt().coerceIn(20, 150)
            val glowColor = Color.argb(
                glowAlpha,
                Color.red(secondaryColor),
                Color.green(secondaryColor),
                Color.blue(secondaryColor)
            )
            overlayPaint.shader = RadialGradient(
                w * 0.5f + panX * 1.5f,
                h * 0.35f,
                glowRadius,
                glowColor,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, overlayPaint)
        }
        overlayPaint.shader = null

        // Instrumental Cinema Letterbox Bars when an INSTRUMENTAL section is active
        if (isInstrumentalSection) {
            val barH = h * 0.042f
            pillPaint.color = Color.argb(185, 8, 7, 16)
            canvas.drawRect(0f, 0f, w, barH, pillPaint)
            canvas.drawRect(0f, h - barH, w, h, pillPaint)
        }

        // Visual Transition execution at Storyboard Cue boundaries (Flash Cut / Cross-Dissolve / Fade)
        if (boundaryFactor > 0.02f) {
            when {
                "flash" in transitionType || "strobe" in transitionType -> {
                    val flashAlpha = (boundaryFactor * 145f).toInt().coerceIn(0, 160)
                    transitionPaint.color = Color.argb(
                        flashAlpha,
                        Color.red(secondaryColor),
                        Color.green(secondaryColor),
                        Color.blue(secondaryColor)
                    )
                    canvas.drawRect(0f, 0f, w, h, transitionPaint)
                }
                "fade" in transitionType || "black" in transitionType -> {
                    val fadeAlpha = (boundaryFactor * 170f).toInt().coerceIn(0, 190)
                    transitionPaint.color = Color.argb(fadeAlpha, 10, 9, 18)
                    canvas.drawRect(0f, 0f, w, h, transitionPaint)
                }
                else -> {
                    // Cross-dissolve soft violet veil at scene handoff
                    val dissolveAlpha = (boundaryFactor * 85f).toInt().coerceIn(0, 100)
                    transitionPaint.color = Color.argb(
                        dissolveAlpha,
                        Color.red(primaryColor),
                        Color.green(primaryColor),
                        Color.blue(primaryColor)
                    )
                    canvas.drawRect(0f, 0f, w, h, transitionPaint)
                }
            }
        }

        // 7. Floating studio light particles scaled by cue lighting & PCM bass energy
        val particleCount = if (modelType == VideoModelType.CINEMA_PRO || isChorusSection) 22 else 12
        for (p in 0 until particleCount) {
            val seed = p * 37.7f
            val px = ((sin(seed + timeSec * 0.35f) * 0.5f + 0.5f) * w)
            val py = ((1f - ((timeSec * (0.08f + (p % 5) * 0.02f) + p * 0.17f) % 1f)) * h)
            val pRadius = (w * 0.004f) * (1f + (p % 3) * 0.65f + bassEnergy * 0.7f)
            val pColor = if (p % 2 == 0) secondaryColor else primaryColor
            particlePaint.color = Color.argb(
                ((80 + bassEnergy * 110f) * cueLighting).toInt().coerceIn(35, 220),
                Color.red(pColor),
                Color.green(pColor),
                Color.blue(pColor)
            )
            canvas.drawCircle(px, py, pRadius, particlePaint)
        }

        // 8. Top header badge (Song title + Active Storyboard Scene Indicator)
        val pad = w * 0.048f
        val pillHeight = (h * 0.044f).coerceIn(32f, 56f)
        pillPaint.color = Color.argb(165, 19, 17, 34)
        val badgeWidth = (w * 0.64f).coerceAtMost(w - pad * 2)
        val headerRect = RectF(pad, pad, pad + badgeWidth, pad + pillHeight)
        canvas.drawRoundRect(headerRect, pillHeight / 2f, pillHeight / 2f, pillPaint)

        subTextPaint.color = Color.WHITE
        subTextPaint.textSize = (pillHeight * 0.40f).coerceIn(13f, 22f)
        val cleanSong = if (songTitle.length > 26) songTitle.take(24) + "…" else songTitle
        canvas.drawText(
            "♫ $cleanSong",
            pad + pillHeight * 0.42f,
            pad + pillHeight * 0.64f,
            subTextPaint
        )

        // Active Storyboard Scene Pill (bottom-left above equalizer)
        if (activeCue != null) {
            cueTagPaint.textSize = (pillHeight * 0.34f).coerceIn(11f, 18f)
            cueTagPaint.color = Color.parseColor("#A8F240")
            val sceneTag = "${activeCue.sectionType}: ${activeCue.sceneTitle} (${effectiveCamera.label})"
            canvas.drawText(
                sceneTag.take(44),
                w - pad,
                h * 0.80f,
                cueTagPaint
            )
        }

        // 9. Lyric / Visual Caption Overlay (honors EDL lyric edits or fallback lyrics)
        val effectiveLyric = if (projectEdl != null) {
            resolvedEdl?.activeLyric?.toLyricLine()
        } else {
            lyrics.firstOrNull { timeSec >= it.startSec && timeSec <= it.endSec }
        }
        if (lyricsEnabled && effectiveLyric != null && effectiveLyric.text.isNotBlank()) {
            val activeLyric = effectiveLyric
            val lyricBoxWidth = w * 0.86f
            val lyricBoxHeight = (h * 0.105f).coerceIn(62f, 130f)
            val lyricTop = h * 0.67f
                val lyricRect = RectF(
                    (w - lyricBoxWidth) / 2f,
                    lyricTop,
                    (w + lyricBoxWidth) / 2f,
                    lyricTop + lyricBoxHeight
                )

                pillPaint.color = if (activeLyric.isChorusHighlight || isChorusSection) {
                    Color.argb(205, 79, 53, 210)
                } else {
                    Color.argb(175, 19, 17, 34)
                }
                canvas.drawRoundRect(lyricRect, 24f, 24f, pillPaint)

                // Source & timing uncertainty micro-label
                textPaint.textSize = (w * 0.024f).coerceIn(10f, 16f)
                textPaint.color = Color.parseColor("#00E5FF")
                canvas.drawText(
                    activeLyric.sourceType.badgeLabel,
                    w / 2f,
                    lyricRect.top + textPaint.textSize * 1.25f,
                    textPaint
                )

                textPaint.textSize = (w * 0.040f).coerceIn(17f, 36f)
                textPaint.color = if (activeLyric.isChorusHighlight) {
                    Color.parseColor("#A8F240")
                } else {
                    Color.WHITE
                }

                val lineText = activeLyric.text
                if (lineText.length <= 36) {
                    canvas.drawText(
                        lineText,
                        w / 2f,
                        lyricRect.centerY() + textPaint.textSize * 0.55f,
                        textPaint
                    )
                } else {
                    val mid = lineText.lastIndexOf(' ', 34).takeIf { it > 10 } ?: 32
                    val line1 = lineText.substring(0, mid).trim()
                    val line2 = lineText.substring(mid).trim().take(38)
                    canvas.drawText(
                        line1,
                        w / 2f,
                        lyricRect.centerY() + textPaint.textSize * 0.05f,
                        textPaint
                    )
                    canvas.drawText(
                        line2,
                        w / 2f,
                        lyricRect.centerY() + textPaint.textSize * 1.05f,
                        textPaint
                    )
                }
        }

        // 10. Bottom 24-Band Audio-Reactive Equalizer Bars (driven by decoded PCM spectrum)
        val eqLeft = w * 0.08f
        val eqRight = w * 0.92f
        val eqWidth = eqRight - eqLeft
        val bandCount = spectrum.size
        val slotWidth = eqWidth / bandCount
        val barWidth = slotWidth * 0.62f
        val maxBarHeight = h * 0.11f
        val eqBottom = h * 0.94f

        barPaint.shader = LinearGradient(
            eqLeft, eqBottom - maxBarHeight, eqRight, eqBottom,
            intArrayOf(secondaryColor, primaryColor, Color.parseColor("#00E5FF")),
            null,
            Shader.TileMode.CLAMP
        )

        for (i in 0 until bandCount) {
            val amp = spectrum[i].coerceIn(0.04f, 1.0f)
            val bh = (maxBarHeight * amp).coerceAtLeast(4f)
            val left = eqLeft + i * slotWidth + (slotWidth - barWidth) / 2f
            val top = eqBottom - bh
            val rect = RectF(left, top, left + barWidth, eqBottom)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, barPaint)
        }
        barPaint.shader = null
    }
}
