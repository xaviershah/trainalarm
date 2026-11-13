package com.trainalarm.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trainalarm.app.TimeText
import com.trainalarm.app.theme.Anton
import com.trainalarm.app.theme.Doto
import com.trainalarm.app.theme.LocalColors
import com.trainalarm.app.theme.PaletteTokens
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Two dots, drawn by us: the Doto colon glyph reads as a plus sign. */
@Composable
fun ColonDots(size: Dp, color: Color) {
    Column(Modifier.width(size * 0.3f), Arrangement.spacedBy(size * 0.16f), Alignment.CenterHorizontally) {
        repeat(2) { Box(Modifier.size(size * 0.13f).background(color, CircleShape)) }
    }
}

/** A time or countdown in the dot-matrix face. */
@Composable
fun DotTime(hm: TimeText.HM, size: Dp, color: Color, modifier: Modifier = Modifier) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Row(modifier.clearAndSetSemantics { contentDescription = hm.plain }, verticalAlignment = Alignment.CenterVertically) {
        Text(hm.hours, fontFamily = Doto, fontWeight = FontWeight.Bold, fontSize = fontSize, lineHeight = fontSize, color = color, maxLines = 1)
        ColonDots(size, color)
        Text(hm.minutes, fontFamily = Doto, fontWeight = FontWeight.Bold, fontSize = fontSize, lineHeight = fontSize, color = color, maxLines = 1)
    }
}

enum class PillStyle { PLAIN, LATE, BAD, ON_TEAL }

/**
 * Text colour and fill (null = no fill) as palette tokens. Orange and teal are fills only, so neither is
 * ever a text colour; teal fills carry the `onTeal` text colour.
 */
fun pillTokens(style: PillStyle, t: PaletteTokens): Pair<Int, Int?> = when (style) {
    PillStyle.PLAIN -> t.text to t.surface2
    PillStyle.LATE -> t.onAccent to t.accent
    PillStyle.BAD -> t.bad to null
    PillStyle.ON_TEAL -> t.onTeal to t.teal
}

@Composable
fun Pill(text: String, style: PillStyle = PillStyle.PLAIN, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val (foregroundToken, backgroundToken) = pillTokens(style, c.tokens)
    val foreground = Color(0xFF000000.toInt() or foregroundToken)
    val background = backgroundToken?.let { Color(0xFF000000.toInt() or it) } ?: Color.Transparent
    val border = if (style == PillStyle.BAD) Modifier.border(1.5.dp, c.bad, CircleShape) else Modifier
    Text(text, color = foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(CircleShape).background(background).then(border).padding(horizontal = 11.dp, vertical = 4.dp))
}

enum class ButtonStyle { ACCENT, GHOST, CREAM }

@Composable
fun BigButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: ButtonStyle = ButtonStyle.ACCENT, enabled: Boolean = true) {
    val c = LocalColors.current
    val foreground = when (style) {
        ButtonStyle.ACCENT -> c.onAccent
        ButtonStyle.GHOST -> c.head
        ButtonStyle.CREAM -> Color(0xFF201D1C)
    }
    val background = when (style) {
        ButtonStyle.ACCENT -> c.accent
        ButtonStyle.GHOST -> Color.Transparent
        ButtonStyle.CREAM -> Color(0xFFF3ECE3)
    }
    val shape = RoundedCornerShape(18.dp)
    val outline = if (style == ButtonStyle.GHOST) Modifier.border(1.5.dp, c.muted.copy(alpha = 0.5f), shape) else Modifier
    Box(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f).clip(shape).background(background).then(outline)
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(title.uppercase(), fontFamily = Anton, fontSize = 26.sp, color = foreground, letterSpacing = 0.5.sp)
    }
}

/** The next choice up or down from [selected], clamped at both ends. Nothing chosen starts at the first choice. */
fun stepLead(choices: List<Int>, selected: Int?, up: Boolean): Int {
    val index = selected?.let { choices.indexOf(it) }?.coerceAtLeast(0) ?: 0
    return choices[if (up) minOf(choices.size - 1, index + 1) else maxOf(0, index - 1)]
}

/** Picks a lead time from [choices]: tap or drag along the tick ruler. The marker sits on the choice. */
@Composable
fun LeadRuler(choices: List<Int>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val inset = 16.dp
    fun pick(x: Float, width: Float, insetPx: Float): Int {
        val fraction = ((x - insetPx) / max(1f, width - insetPx * 2)).coerceIn(0f, 1f)
        return choices[(fraction * (choices.size - 1)).roundToInt()]
    }
    Column(
        modifier.fillMaxWidth().semantics {
            contentDescription = "Minutes before arrival"
            stateDescription = selected?.let { "$it minutes" } ?: "not chosen"
            // Adjustable for TalkBack: the same steps as the iOS adjustable action, through the same onSelect.
            progressBarRangeInfo = ProgressBarRangeInfo(
                (selected?.let { choices.indexOf(it) }?.coerceAtLeast(0) ?: 0).toFloat(), 0f..(choices.size - 1).toFloat(), choices.size - 2)
            setProgress { value -> onSelect(choices[value.roundToInt().coerceIn(0, choices.size - 1)]); true }
            customActions = listOf(
                CustomAccessibilityAction("Increase minutes") { onSelect(stepLead(choices, selected, up = true)); true },
                CustomAccessibilityAction("Decrease minutes") { onSelect(stepLead(choices, selected, up = false)); true }
            )
        }
    ) {
        Box(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2)
                .pointerInput(choices) { detectTapGestures { onSelect(pick(it.x, size.width.toFloat(), inset.toPx())) } }
                .pointerInput(choices) { detectHorizontalDragGestures { change, _ -> onSelect(pick(change.position.x, size.width.toFloat(), inset.toPx())) } }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                var x = 4.5.dp.toPx()
                while (x < size.width) {
                    drawRect(c.muted, Offset(x, size.height * 0.28f), androidx.compose.ui.geometry.Size(1.5.dp.toPx(), size.height * 0.44f))
                    x += 9.dp.toPx()
                }
                val index = selected?.let { choices.indexOf(it) } ?: -1
                if (index >= 0) {
                    val usable = size.width - inset.toPx() * 2
                    val markerX = inset.toPx() + usable * index / (choices.size - 1) - 1.5.dp.toPx()
                    drawRect(c.accent, Offset(markerX, 0f), androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp).padding(top = 4.dp)) {
            val usable = maxWidth - inset * 2
            choices.forEachIndexed { index, minutes ->
                Text(
                    minutes.toString(), fontFamily = Doto, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    color = if (minutes == selected) c.head else c.muted,
                    modifier = Modifier.offset(x = inset + usable * (index.toFloat() / (choices.size - 1)) - 8.dp)
                )
            }
        }
    }
}

/** A round dial: the alarm time (orange dot) and the arrival time (cream dot) at their minutes past the hour. */
@Composable
fun AlarmDial(alarmAt: java.time.Instant?, arrival: java.time.Instant, lead: Int?, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val cream = Color(0xFFF3ECE3)
    Box(
        modifier.aspectRatio(1f).clearAndSetSemantics {
            contentDescription = lead?.let { "Alarm $it minutes before arrival" } ?: "No alarm time chosen"
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = min(size.width, size.height) / 2
            drawCircle(c.surface2, radius, center)
            fun point(r: Float, angle: Double) = Offset(center.x + r * sin(angle).toFloat(), center.y - r * cos(angle).toFloat())
            for (tick in 0 until 60) {
                val angle = tick * Math.PI / 30
                drawLine(c.muted, point(radius * 0.80f, angle), point(radius * 0.86f, angle), 1.4.dp.toPx())
            }
            fun dot(instant: java.time.Instant, color: Color) {
                val minute = TimeText.clock(instant).minutes.toDoubleOrNull() ?: 0.0
                drawCircle(color, 6.dp.toPx(), point(radius * 0.83f, minute * Math.PI / 30))
            }
            dot(arrival, c.head)
            if (alarmAt != null) dot(alarmAt, c.accent)
            drawCircle(Color(0xFF201D1C), radius - 34.dp.toPx(), center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(lead?.toString() ?: "-", fontFamily = Doto, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = cream)
            Text("min before", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = cream)
        }
    }
}
