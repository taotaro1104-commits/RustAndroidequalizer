package dev.rusteq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rusteq.audio.EqConfig
import dev.rusteq.audio.responseDb
import kotlin.math.*

internal enum class AudioSymbol(val path: String) {
    Sound("M5 3v18 M12 3v18 M19 3v18 M2 8h6 M9 16h6 M16 10h6"),
    Space("M4 15v-3a8 8 0 0 1 16 0v3 M4 13H2v7h5v-7z M20 13h2v7h-5v-7z"),
    Tools("M3 6h18 M3 12h18 M3 18h18 M7 3v6 M16 9v6 M10 15v6"),
    Music("M9 18V5l11-2v13 M9 8l11-2 M9 18a3 2 0 1 1-3-2c1.7 0 3 .9 3 2 M20 16a3 2 0 1 1-3-2c1.7 0 3 .9 3 2"),
    Folder("M3 6h7l2 2h9v12H3z M3 6V4h7l2 2"),
    Play("M8 4l12 8-12 8z"),
    Pause("M7 5v14 M17 5v14"),
    Previous("M5 5v14 M19 5L8 12l11 7z"),
    Next("M19 5v14 M5 5l11 7-11 7z"),
    Check("M4 12l5 5L20 6"),
    Chevron("M6 9l6 6 6-6"),
    Save("M4 3h13l4 4v14H3V3z M7 3v6h10V3 M7 21v-8h10v8"),
    Import("M12 3v12 M7 10l5 5 5-5 M4 16v5h16v-5"),
    Export("M12 16V4 M7 9l5-5 5 5 M4 16v5h16v-5"),
}

private val AudioVectors = AudioSymbol.entries.associateWith { symbol ->
    ImageVector.Builder(symbol.name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = PathParser().parsePathString(symbol.path).toNodes(),
        stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()
}

@Composable
internal fun AudioIcon(symbol: AudioSymbol, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(AudioVectors.getValue(symbol), description, modifier.size(24.dp), tint)
}

@Composable
internal fun PresetCurve(gains: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(32.dp)) {
        drawLine(color.copy(alpha = .2f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
        val path = Path()
        gains.forEachIndexed { i, gain ->
            val x = i.toFloat() / (gains.size - 1).coerceAtLeast(1) * size.width
            val y = size.height * (.5f - gain / 16f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
internal fun SoundCurve(config: EqConfig, selected: Int, sampleRate: Float, onSelect: (Int) -> Unit, onEdit: (Int, Float, Float) -> Unit) {
    val current by rememberUpdatedState(config)
    val select by rememberUpdatedState(onSelect)
    val edit by rememberUpdatedState(onEdit)
    val curve = remember(config, sampleRate) { List(240) { i -> config.responseDb(20f * 1000f.pow(i / 239f), sampleRate) } }
    var dragging by remember { mutableIntStateOf(0) }
    fun nearest(p: Offset, width: Int, height: Int): Int = current.bands.indices.minBy { i ->
        val b = current.bands[i]
        val x = log10(b.hz.coerceAtLeast(20f) / 20f) / 3f * width
        val y = (24f - b.db) / 48f * height
        (p.x - x).pow(2) + (p.y - y).pow(2)
    }
    Canvas(Modifier.fillMaxWidth().height(168.dp)
        .semantics { contentDescription = "音質カーブ。下の詳細調整でも周波数とゲインを変更できます" }
        .pointerInput(Unit) { detectTapGestures { p -> select(nearest(p, size.width, size.height)) } }
        .pointerInput(Unit) { detectDragGestures(onDragStart = { p -> dragging = nearest(p, size.width, size.height); select(dragging) }) { change, _ ->
            change.consume()
            edit(dragging, 20f * 1000f.pow((change.position.x / size.width).coerceIn(0f, 1f)), (24f - change.position.y / size.height * 48f).coerceIn(-24f, 24f))
        } }
    ) {
        val line = if (config.bypass) AudioColors.Muted else AudioColors.Blue
        listOf(-12f, 0f, 12f).forEach { db ->
            val y = (24f - db) / 48f * size.height
            drawLine(AudioColors.Line, Offset(0f, y), Offset(size.width, y), if (db == 0f) 1.5.dp.toPx() else 1.dp.toPx())
        }
        listOf(100f, 1000f, 10000f).forEach { hz ->
            val x = log10(hz / 20f) / 3f * size.width
            drawLine(AudioColors.Line.copy(alpha = .65f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
        val path = Path()
        curve.forEachIndexed { i, db ->
            val x = i / 239f * size.width
            val y = ((24f - db) / 48f).coerceIn(0f, 1f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, line.copy(alpha = .055f))
        drawPath(path, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        config.bands.forEachIndexed { i, b ->
            val p = Offset(log10(b.hz.coerceAtLeast(20f) / 20f).coerceIn(0f, 3f) / 3f * size.width, (24f - b.db) / 48f * size.height)
            if (i == selected) drawCircle(line.copy(alpha = .13f), 12.dp.toPx(), p)
            drawCircle(if (i == selected) line else Color.White, if (i == selected) 5.dp.toPx() else 3.dp.toPx(), p)
            if (i != selected) drawCircle(line.copy(alpha = .65f), 3.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
internal fun StereoScene(mode: Int, width: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(108.dp)) {
        val center = Offset(size.width * .5f, size.height * .66f)
        val spread = size.width * if (mode == 1) (.18f + width.coerceIn(0f, 2f) * .09f) else .23f
        val left = Offset(center.x - spread, size.height * .24f)
        val right = Offset(center.x + spread, size.height * .24f)
        val color = AudioColors.Green
        drawLine(color.copy(alpha = .3f), left, center, 2.dp.toPx())
        drawLine(color.copy(alpha = .3f), right, center, 2.dp.toPx())
        listOf(left, right).forEach { p ->
            drawRoundRect(color, Offset(p.x - 12.dp.toPx(), p.y - 16.dp.toPx()), androidx.compose.ui.geometry.Size(24.dp.toPx(), 32.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
            drawCircle(Color.White.copy(alpha = .9f), 5.dp.toPx(), p)
        }
        drawCircle(Color.White, 17.dp.toPx(), center)
        drawCircle(color, 8.dp.toPx(), center)
        drawArc(color, 200f, 140f, false, Offset(center.x - 15.dp.toPx(), center.y - 15.dp.toPx()), androidx.compose.ui.geometry.Size(30.dp.toPx(), 30.dp.toPx()), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}
