package com.example.videoeditor

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private val Green = Color(0xFF22E06B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Green, onPrimary = Color.Black,
                    background = Color.Black, surface = Color(0xFF0B0B0B)
                )
            ) { Surface(Modifier.fillMaxSize(), color = Color.Black) { AppRoot() } }
        }
    }
}

private fun seekGlobal(p: ExoPlayer, segs: List<Seg>, g: Long) {
    var rem = g
    for ((i, s) in segs.withIndex()) {
        if (rem <= s.len || i == segs.lastIndex) { p.seekTo(i, rem.coerceIn(0, s.len)); return }
        rem -= s.len
    }
}

@UnstableApi
@Composable
fun EditorScreen(initialUri: Uri?, initialTab: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val fonts = remember { loadFonts(ctx) }
    var uri by remember { mutableStateOf(initialUri) }
    var segs by remember { mutableStateOf(initialUri?.let { listOf(Seg(0, durationOf(ctx, it))) } ?: listOf()) }
    var grade by remember { mutableStateOf(Grade()) }
    var text by remember { mutableStateOf(TextCfg()) }
    var tab by remember { mutableIntStateOf(initialTab) }
    var global by remember { mutableLongStateOf(0L) }
    var exportP by remember { mutableIntStateOf(-1) }
    var msg by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }

    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val total = segs.sumOf { it.len }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) {
            val r = MediaMetadataRetriever()
            r.setDataSource(ctx, u)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            r.release()
            uri = u; global = 0; segs = listOf(Seg(0, d))
        }
    }

    // rebuild playlist whenever the cut list changes, keep the playhead where it was
    LaunchedEffect(uri, segs) {
        val u = uri ?: return@LaunchedEffect
        player.setMediaItems(segs.map { s ->
            MediaItem.Builder().setUri(u).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(s.start).setEndPositionMs(s.end).build()
            ).build()
        })
        player.prepare()
        seekGlobal(player, segs, global)
    }
    // live preview of filter + text (same effects as the export)
    LaunchedEffect(grade, text, uri) {
        player.setVideoEffects(allEffects(grade, text, fonts[text.font].typeface))
    }
    // playhead
    LaunchedEffect(segs) {
        while (true) {
            delay(100)
            val i = player.currentMediaItemIndex
            if (i in segs.indices) {
                global = segs.take(i).sumOf { it.len } + player.currentPosition.coerceAtLeast(0)
            }
            playing = player.isPlaying
        }
    }

    Column(Modifier.fillMaxSize().padding(10.dp).verticalScroll(rememberScrollState())) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = false } },
            modifier = Modifier.fillMaxWidth().height(300.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back", color = Green) }
            Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) { Text("Video") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (player.isPlaying) player.pause() else player.play() }, enabled = uri != null) {
                Text(if (playing) "Pause" else "Play")
            }
            Spacer(Modifier.width(8.dp))
            Text("${global / 1000}s / ${total / 1000}s")
        }
        Slider(
            value = global.toFloat(), valueRange = 0f..(total.coerceAtLeast(1)).toFloat(),
            onValueChange = { global = it.toLong(); seekGlobal(player, segs, global) },
            enabled = uri != null
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Edit", "Text", "Filter", "Color", "Export").forEachIndexed { i, n ->
                TextButton(onClick = { tab = i }) {
                    Text(n, color = if (tab == i) Green else Color.Gray)
                }
            }
        }
        when (tab) {
            0 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = uri != null, onClick = {
                    val i = player.currentMediaItemIndex
                    val p = player.currentPosition
                    val s = segs.getOrNull(i) ?: return@Button
                    if (p > 300 && p < s.len - 300) {
                        segs = segs.toMutableList().also {
                            it[i] = Seg(s.start, s.start + p)
                            it.add(i + 1, Seg(s.start + p, s.end))
                        }
                    }
                }) { Text("Split") }
                Button(enabled = segs.size > 1, onClick = {
                    val i = player.currentMediaItemIndex
                    if (i in segs.indices) {
                        segs = segs.toMutableList().also { it.removeAt(i) }
                        global = global.coerceAtMost(segs.sumOf { it.len })
                    }
                }) { Text("Delete clip") }
                Text("Clips: ${segs.size}", Modifier.align(Alignment.CenterVertically))
            }
            1 -> Column {
                OutlinedTextField(
                    value = text.text, onValueChange = { text = text.copy(text = it) },
                    label = { Text("Text") }, modifier = Modifier.fillMaxWidth()
                )
                Text("Size"); Slider(value = text.size.toFloat(), valueRange = 20f..200f,
                    onValueChange = { text = text.copy(size = it.toInt()) })
                Text("Position (down - up)"); Slider(value = text.y, valueRange = -0.9f..0.9f,
                    onValueChange = { text = text.copy(y = it) })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(0xFFFFFFFF, 0xFF22E06B, 0xFFFF3B30, 0xFFFFD60A, 0xFF0A84FF, 0xFFFF2D92, 0xFF000000).forEach { c ->
                        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                            .clickable { text = text.copy(color = c.toInt()) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(fonts) { i, f ->
                        OutlinedButton(onClick = { text = text.copy(font = i) }) {
                            Text(f.name, fontFamily = FontFamily(f.typeface),
                                color = if (text.font == i) Green else Color.White)
                        }
                    }
                }
            }
            2 -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(presets) { _, p ->
                    OutlinedButton(onClick = { grade = p.grade }) { Text(p.name) }
                }
            }
            3 -> Column {
                Text("Brightness"); Slider(grade.brightness, { grade = grade.copy(brightness = it) }, valueRange = -0.5f..0.5f)
                Text("Contrast"); Slider(grade.contrast, { grade = grade.copy(contrast = it) }, valueRange = -0.5f..0.5f)
                Text("Saturation"); Slider(grade.saturation, { grade = grade.copy(saturation = it) }, valueRange = -100f..100f)
                Text("Warmth (cool - warm)"); Slider(grade.warmth, { grade = grade.copy(warmth = it) }, valueRange = -1f..1f)
                TextButton(onClick = { grade = Grade() }) { Text("Reset") }
            }
            4 -> Column {
                Text("Export quality (4K needs a phone that can encode 4K)")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("720p" to 720, "1080p" to 1080, "2K" to 1440, "4K" to 2160).forEach { (n, h) ->
                        Button(enabled = uri != null && exportP < 0, onClick = {
                            msg = ""; exportP = 0
                            exportVideo(ctx, uri!!, segs, allEffects(grade, text, fonts[text.font].typeface), h,
                                { exportP = it },
                                { saved, err ->
                                    exportP = -1
                                    msg = if (saved != null) "Saved: Movies/VideoEditor" else "Error: $err"
                                })
                        }) { Text(n) }
                    }
                }
                if (exportP >= 0) {
                    LinearProgressIndicator(progress = { exportP / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("$exportP%")
                }
                if (msg.isNotEmpty()) Text(msg, color = Green)
            }
        }
    }
}
