package com.example.videoeditor

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Green = Color(0xFF22E06B)
private val Ink = Color(0xFF050505)

@UnstableApi
@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf("home") }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    val vidPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) { uri = u; screen = "editor" }
    }
    BackHandler(screen == "editor") { screen = "home" }
    if (screen == "home") {
        HomeScreen(onOpen = { t ->
            tab = t
            vidPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        })
    } else {
        EditorScreen(uri, tab, onBack = { screen = "home" })
    }
}

@UnstableApi
@Composable
fun HomeScreen(onOpen: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("ui", 0) }
    val bgFile = remember { File(ctx.filesDir, "header_bg") }
    var bgType by remember { mutableStateOf(prefs.getString("bg_type", "none") ?: "none") }
    var bgVer by remember { mutableIntStateOf(0) }

    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) scope.launch {
            val isVideo = ctx.contentResolver.getType(u)?.startsWith("video") == true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(u)!!.use { i -> bgFile.outputStream().use { o -> i.copyTo(o) } }
                }.isSuccess
            }
            if (ok) {
                bgType = if (isVideo) "video" else "image"
                prefs.edit().putString("bg_type", bgType).apply()
                bgVer++
            }
        }
    }
    fun pickBg() = bgPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))

    val hasBg = bgType != "none" && bgFile.exists()

    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState())) {
        // ---- header: your photo or a live video (first 5 sec, looped) ----
        Box(Modifier.fillMaxWidth().height(290.dp)) {
            if (!hasBg) {
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFF0C3B22), Color(0xFF071A10), Ink))))
                Box(
                    Modifier.align(Alignment.Center).width(300.dp).padding(14.dp)
                        .drawBehind {
                            drawRoundRect(Green, cornerRadius = CornerRadius(14.dp.toPx()),
                                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))))
                        }.clickable { pickBg() }.padding(14.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Tap: apni photo ya 5 sec ka live video lagao", color = Color(0xFF9BE9BC), fontSize = 12.sp) }
            } else if (bgType == "image") {
                val bmp = remember(bgVer) { decodeSampled(bgFile, 1200) }
                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                VideoHeader(bgFile, bgVer)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC050505)))))
            TextButton(onClick = { onOpen(0) }, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Text("Import", color = Green)
            }
            Button(
                onClick = { pickBg() },
                colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black),
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) { Text("Change BG", fontSize = 12.sp) }
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text("Video create", color = Color(0xFF9BE9BC), fontSize = 12.sp)
                Text("Get started", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        // ---- cards ----
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeCard("+", "New video", 1f, Modifier.weight(1f)) { onOpen(0) }
            HomeCard("▣", "Edit photo (soon)", 0.45f, Modifier.weight(1f)) { }
        }
        Spacer(Modifier.height(18.dp))
        // ---- tools (each opens the editor on that tab) ----
        val tools = listOf(
            Triple("✂", "Split & Cut", 0), Triple("Aa", "Text & Fonts", 1), Triple("FX", "Filters", 2),
            Triple("◐", "Color grade", 3), Triple("4K", "Export 4K", 4), Triple("BG", "Header BG", -1)
        )
        tools.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                row.forEach { (g, n, t) ->
                    Column(
                        Modifier.weight(1f).clickable { if (t < 0) pickBg() else onOpen(t) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(52.dp).border(2.dp, Green, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text(g, color = Green, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(8.dp))
                        Text(n, color = Color(0xFFE8E8E8), fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HomeCard(icon: String, label: String, alpha: Float, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(84.dp).alpha(alpha).background(Color.Black, RoundedCornerShape(14.dp))
            .border(1.dp, Green, RoundedCornerShape(14.dp)).clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(icon, color = Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

@UnstableApi
@Composable
private fun VideoHeader(file: File, version: Int) {
    val ctx = LocalContext.current
    val p = remember(version) {
        ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(
                MediaItem.Builder().setUri(Uri.fromFile(file))
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(5000).build())
                    .build()
            )
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(p) { onDispose { p.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { player = p; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM } },
        modifier = Modifier.fillMaxSize()
    )
}

private fun decodeSampled(f: File, maxSide: Int): android.graphics.Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, o)
    var s = 1
    while (o.outWidth / s > maxSide || o.outHeight / s > maxSide) s *= 2
    return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
}
