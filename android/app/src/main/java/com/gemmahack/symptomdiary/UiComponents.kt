package com.gemmahack.symptomdiary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Cream = Color(0xfffaf7f2)
val Ink = Color(0xff25282c)
val Muted = Color(0xff65696d)
val Peach = Color(0xffffdfcb)
val Rust = Color(0xff9c451f)
val LineColor = Color(0xffe7e1d9)
val Lavender = Color(0xffeee8fa)

@Composable
fun Title(text: String, subtitle: String? = null, centered: Boolean = false) {
    Text(text, fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
    if (subtitle != null) Text(subtitle, color = Muted, fontSize = 16.sp, lineHeight = 23.sp,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
}

@Composable
fun Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
fun Secondary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), border = androidx.compose.foundation.BorderStroke(1.dp, LineColor),
        contentPadding = PaddingValues(18.dp, 15.dp)) {
        Text(text, color = if (enabled) Ink else Muted, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color.White.copy(alpha = 0.75f),
        border = androidx.compose.foundation.BorderStroke(1.dp, LineColor.copy(alpha = 0.65f))) {
        Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun EntryRow(symbol: String, title: String, detail: String? = null, tint: Color = Peach, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
        color = Color.White.copy(alpha = 0.7f), shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, LineColor.copy(alpha = 0.55f))) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(49.dp).background(tint, CircleShape), contentAlignment = Alignment.Center) { Glyph(symbol, Rust, 23) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                if (detail != null) Text(detail, fontSize = 14.sp, lineHeight = 20.sp, color = Muted)
            }
            Glyph("chevron", Muted, 18)
        }
    }
}

@Composable
fun Flag(text: String) {
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Peach.copy(alpha = 0.65f)).padding(12.dp, 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph("info", Rust, 19)
        Text(text, color = Rust, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
fun SectionLabel(text: String) { Text(text.uppercase(), color = Muted, fontSize = 12.sp, letterSpacing = 1.sp,
    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }

@Composable
fun CheckRow(text: String, checked: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Checkbox) { onChange(!checked) }
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChange, colors = CheckboxDefaults.colors(checkedColor = Rust))
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 16.sp)
            if (detail != null) Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

/** A quiet, code-drawn voice control; records/settings never use it as decoration. */
@Composable
fun VoiceOrb(active: Boolean, label: String, large: Boolean = true, onClick: () -> Unit) {
    val diameter = if (large) 242.dp else 164.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(diameter).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val radius = size.minDimension / 2
                drawCircle(Brush.radialGradient(listOf(Color(0xfffff7d9), Color(0xfff8d6c0), Color(0xffdce9ef)),
                    center = Offset(size.width * 0.3f, size.height * 0.3f), radius = size.width), radius)
                drawCircle(Color.White.copy(alpha = 0.8f), radius - 2, style = Stroke(2.dp.toPx()))
                drawCircle(Color(0xffb9cfdc).copy(alpha = 0.4f), radius * 0.86f, center + Offset(radius * 0.08f, -radius * 0.05f), style = Stroke(1.dp.toPx()))
                if (active) {
                    for (i in 0..6) {
                        val h = listOf(9f, 19f, 28f, 37f, 28f, 19f, 9f)[i] * density
                        val x = center.x + (i - 3) * 10 * density
                        drawLine(Rust, Offset(x, center.y - h / 2), Offset(x, center.y + h / 2), 4 * density,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                } else {
                    val path = Path().apply { moveTo(center.x - radius * 0.65f, center.y + radius * 0.1f)
                        cubicTo(center.x - radius * 0.15f, center.y - radius * 0.55f, center.x + radius * 0.15f,
                            center.y + radius * 0.65f, center.x + radius * 0.72f, center.y - radius * 0.04f) }
                    drawPath(path, Color.White.copy(alpha = 0.72f), style = Stroke(2.dp.toPx()))
                }
            }
        }
        Text(label, fontSize = 17.sp, color = Muted, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
fun Glyph(name: String, color: Color = Ink, size: Int = 22) {
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.width / 24
        val stroke = Stroke(1.8f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round)
        fun path(vararg points: Pair<Float, Float>) { drawPath(Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(p.first * s, p.second * s) else lineTo(p.first * s, p.second * s) }
        }, color, style = stroke) }
        when (name) {
            "back" -> { path(18f to 12f, 5f to 12f); path(10f to 6f, 4f to 12f, 10f to 18f) }
            "chevron" -> path(9f to 5f, 16f to 12f, 9f to 19f)
            "plus" -> { path(12f to 4f, 12f to 20f); path(4f to 12f, 20f to 12f) }
            "check" -> path(4f to 12f, 10f to 18f, 21f to 5f)
            "mic" -> { drawRoundRect(color, Offset(9*s, 3*s), Size(6*s, 12*s), androidx.compose.ui.geometry.CornerRadius(3*s), style = stroke)
                drawArc(color, 0f, 180f, false, Offset(5*s, 7*s), Size(14*s, 13*s), style = stroke)
                path(12f to 20f, 12f to 23f) }
            "folder" -> path(3f to 20f, 3f to 5f, 10f to 5f, 13f to 8f, 21f to 8f, 21f to 20f, 3f to 20f)
            "paper" -> { path(6f to 3f, 16f to 3f, 20f to 7f, 20f to 22f, 6f to 22f, 6f to 3f)
                path(10f to 11f, 16f to 11f); path(10f to 15f, 16f to 15f); path(10f to 19f, 14f to 19f) }
            "health" -> { path(12f to 5f, 12f to 19f); path(5f to 12f, 19f to 12f) }
            "play" -> path(7f to 3f, 21f to 12f, 7f to 21f, 7f to 3f)
            "pause" -> { path(8f to 4f, 8f to 20f); path(16f to 4f, 16f to 20f) }
            "settings" -> { drawCircle(color, 8*s, style = stroke); drawCircle(color, 3*s, style = stroke)
                for (i in 0..7) { val a = i * Math.PI / 4; drawLine(color,
                    center + Offset(kotlin.math.cos(a).toFloat()*8*s, kotlin.math.sin(a).toFloat()*8*s),
                    center + Offset(kotlin.math.cos(a).toFloat()*11*s, kotlin.math.sin(a).toFloat()*11*s), 2*s) } }
            else -> { drawCircle(color, 9*s, style = stroke); path(12f to 10f, 12f to 17f)
                drawCircle(color, 1*s, Offset(12*s, 6*s)) }
        }
    }
}
