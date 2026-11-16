package com.example.videoeditor

import android.content.ContentValues
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import java.io.File

/** A piece of the source video (milliseconds). Splitting makes two Segs, Delete removes one. */
data class Seg(val start: Long, val end: Long) { val len get() = end - start }

data class Grade(
    val brightness: Float = 0f,   // -0.5 .. 0.5
    val contrast: Float = 0f,     // -0.5 .. 0.5
    val saturation: Float = 0f,   // -100 .. 100
    val warmth: Float = 0f        // -1 .. 1
)

data class Preset(val name: String, val grade: Grade)

val presets = listOf(
    Preset("Normal", Grade()),
    Preset("Vivid", Grade(0.03f, 0.2f, 35f, 0.1f)),
    Preset("Warm", Grade(0.02f, 0.05f, 10f, 0.7f)),
    Preset("Cool", Grade(0f, 0.05f, 5f, -0.7f)),
    Preset("B&W", Grade(0f, 0.2f, -100f, 0f)),
    Preset("Cinema", Grade(-0.05f, 0.3f, -15f, -0.2f)),
    Preset("Fade", Grade(0.08f, -0.25f, -25f, 0.1f))
)

data class TextCfg(
    val text: String = "",
    val size: Int = 60,
    val color: Int = 0xFFFFFFFF.toInt(),
    val font: Int = 0,
    val y: Float = -0.6f          // -1 (bottom) .. 1 (top)
)

class FontItem(val name: String, val typeface: Typeface)

/** System fonts + every .ttf/.otf you drop into app/src/main/assets/fonts/ */
fun loadFonts(ctx: Context): List<FontItem> {
    val list = mutableListOf(
        FontItem("Default", Typeface.DEFAULT),
        FontItem("Bold", Typeface.create(Typeface.DEFAULT, Typeface.BOLD)),
        FontItem("Serif", Typeface.SERIF),
        FontItem("Mono", Typeface.MONOSPACE),
        FontItem("Cursive", Typeface.create("cursive", Typeface.NORMAL)),
        FontItem("Casual", Typeface.create("casual", Typeface.NORMAL))
    )
    ctx.assets.list("fonts")
        ?.filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }
        ?.sorted()
        ?.forEach { f ->
            runCatching {
                list += FontItem(f.substringBeforeLast('.'), Typeface.createFromAsset(ctx.assets, "fonts/$f"))
            }
        }
    return list
}

private class TfSpan(private val tf: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(p: TextPaint) { p.typeface = tf }
    override fun updateMeasureState(p: TextPaint) { p.typeface = tf }
}

@UnstableApi
fun gradeEffects(g: Grade): List<Effect> = buildList {
    if (g.brightness != 0f) add(Brightness(g.brightness))
    if (g.contrast != 0f) add(Contrast(g.contrast))
    if (g.saturation != 0f) add(HslAdjustment.Builder().adjustSaturation(g.saturation).build())
    if (g.warmth != 0f) add(
        RgbAdjustment.Builder()
            .setRedScale(1f + g.warmth * 0.2f)
            .setBlueScale(1f - g.warmth * 0.2f)
            .build()
    )
}

@UnstableApi
fun textEffect(t: TextCfg, tf: Typeface): Effect? {
    if (t.text.isBlank()) return null
    val s = SpannableString(t.text)
    s.setSpan(ForegroundColorSpan(t.color), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    s.setSpan(AbsoluteSizeSpan(t.size), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    s.setSpan(TfSpan(tf), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val settings = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, t.y).build()
    return OverlayEffect(ImmutableList.of(TextOverlay.createStaticTextOverlay(s, settings)))
}

@UnstableApi
fun allEffects(g: Grade, t: TextCfg, tf: Typeface): List<Effect> =
    gradeEffects(g) + listOfNotNull(textEffect(t, tf))

/** Export all segments + effects. height: 720 / 1080 / 1440 / 2160 (4K). Call from the main thread. */
@UnstableApi
fun exportVideo(
    ctx: Context, uri: Uri, segs: List<Seg>, effects: List<Effect>, height: Int,
    onProgress: (Int) -> Unit, onDone: (Uri?, String?) -> Unit
) {
    val main = Handler(Looper.getMainLooper())
    val out = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")
    val items = segs.map { s ->
        val mi = MediaItem.Builder().setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(s.start).setEndPositionMs(s.end).build()
            ).build()
        EditedMediaItem.Builder(mi).build()
    }
    val seq = EditedMediaItemSequence.Builder(*items.toTypedArray()).build()
    val composition = Composition.Builder(seq)
        .setEffects(Effects(emptyList(), effects + Presentation.createForHeight(height)))
        .build()

    val bitrate = when {
        height >= 2160 -> 45_000_000
        height >= 1440 -> 25_000_000
        height >= 1080 -> 14_000_000
        else -> 7_000_000
    }
    val encoder = DefaultEncoderFactory.Builder(ctx)
        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
        .build()

    var finished = false
    lateinit var transformer: Transformer
    val ph = ProgressHolder()
    val poll = object : Runnable {
        override fun run() {
            if (finished) return
            if (transformer.getProgress(ph) != Transformer.PROGRESS_STATE_NOT_STARTED) onProgress(ph.progress)
            main.postDelayed(this, 300)
        }
    }
    transformer = Transformer.Builder(ctx)
        .setEncoderFactory(encoder)
        .setVideoMimeType(if (height >= 2160) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(c: Composition, r: ExportResult) {
                finished = true
                Thread {
                    val saved = runCatching {
                        val cv = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, out.name)
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoEditor")
                        }
                        val dst = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
                        ctx.contentResolver.openOutputStream(dst)!!.use { o -> out.inputStream().use { it.copyTo(o) } }
                        out.delete()
                        dst
                    }
                    main.post { onDone(saved.getOrNull(), saved.exceptionOrNull()?.message) }
                }.start()
            }
            override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                finished = true
                main.post { onDone(null, e.message ?: "export failed") }
            }
        })
        .build()
    transformer.start(composition, out.absolutePath)
    main.post(poll)
}

fun durationOf(ctx: Context, uri: Uri): Long {
    val r = android.media.MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
    } finally { r.release() }
}
