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

/** "1 minute", "7 minutes". */
fun minutesText(n: Int): String = if (n == 1) "1 minute" else "$n minutes"

/** The line above the lead-time ruler: what is chosen, in words. */
fun leadValueLine(minutes: Int?): String = minutes?.let { "${minutesText(it)} before arrival" } ?: "Not chosen"

/**
 * Where the ruler puts each choice, in pixels from its left edge. Pure, so it is tested without drawing:
 * the ticks, the marker, the labels and tap/drag all use these same positions.
 */
data class RulerLayout(val count: Int, val width: Float, val inset: Float) {
    val spacing: Float get() = if (count > 1) maxOf(0f, width - inset * 2) / (count - 1) else 0f

    /** The x of the choice at [index]. */
    fun x(index: Int): Float = inset + spacing * index

    /** The choice nearest to [x], clamped to the first and last. */
    fun indexAt(x: Float): Int {
        if (count < 2 || spacing <= 0f) return 0
        val raw = (x - inset) / spacing
        return if (raw.isFinite()) raw.roundToInt().coerceIn(0, count - 1) else 0
    }

    companion object {
        /** Ticks at multiples of 5 are taller and carry a label. */
        fun isMajor(minutes: Int): Boolean = minutes % 5 == 0
    }
}

/** The choice for a TalkBack progress value (an index into [choices]), clamped to the ends. */
fun leadForProgress(choices: List<Int>, value: Float): Int = choices[value.roundToInt().coerceIn(0, choices.size - 1)]

/** The next choice up or down from [selected], clamped at both ends. Nothing chosen starts at the first choice. */
fun stepLead(choices: List<Int>, selected: Int?, up: Boolean): Int {
    val index = selected?.let { choices.indexOf(it) }?.coerceAtLeast(0) ?: 0
    return choices[if (up) minOf(choices.size - 1, index + 1) else maxOf(0, index - 1)]
}

/** Picks a lead time from [choices]: tap or drag along the tick ruler. The marker sits on the choice, and a line above
 *  the ruler says what is chosen. One tick per choice; ticks at multiples of 5 are taller and labelled. */
@Composable
fun LeadRuler(choices: List<Int>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val inset = 16.dp
    val density = LocalDensity.current
    fun layoutFor(widthPx: Float) = RulerLayout(choices.size, widthPx, with(density) { inset.toPx() })
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "Minutes before arrival"
            stateDescription = selected?.let { minutesText(it) } ?: "not chosen"
            // Adjustable for TalkBack: the same steps as the iOS adjustable action, through the same onSelect.
            progressBarRangeInfo = ProgressBarRangeInfo(
                (selected?.let { choices.indexOf(it) }?.coerceAtLeast(0) ?: 0).toFloat(), 0f..(choices.size - 1).toFloat(), choices.size - 2)
            setProgress { value -> onSelect(leadForProgress(choices, value)); true }
            customActions = listOf(
                CustomAccessibilityAction("Increase minutes") { onSelect(stepLead(choices, selected, up = true)); true },
                CustomAccessibilityAction("Decrease minutes") { onSelect(stepLead(choices, selected, up = false)); true }
            )
        }
    ) {
        Text(
            leadValueLine(selected), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected == null) c.muted else c.head, modifier = Modifier.padding(bottom = 8.dp)
        )
        Box(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2)
                .pointerInput(choices) { detectTapGestures { onSelect(choices[layoutFor(size.width.toFloat()).indexAt(it.x)]) } }
                .pointerInput(choices) {
                    detectHorizontalDragGestures { change, _ -> onSelect(choices[layoutFor(size.width.toFloat()).indexAt(change.position.x)]) }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val layout = RulerLayout(choices.size, size.width, inset.toPx())
                val tickWidth = 1.5.dp.toPx()
                choices.forEachIndexed { index, minutes ->
                    val height = size.height * if (RulerLayout.isMajor(minutes)) 0.52f else 0.36f
                    drawRect(c.muted, Offset(layout.x(index) - tickWidth / 2, (size.height - height) / 2),
                        androidx.compose.ui.geometry.Size(tickWidth, height))
                }
                val index = selected?.let { choices.indexOf(it) } ?: -1
                if (index >= 0) {
                    drawRect(c.accent, Offset(layout.x(index) - 1.5.dp.toPx(), 0f),
                        androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp).padding(top = 4.dp)) {
            val layout = layoutFor(with(density) { maxWidth.toPx() })
            choices.forEachIndexed { index, minutes ->
                if (RulerLayout.isMajor(minutes)) {
                    // A 32 dp slot centred on the tick, so two-digit labels sit under their tick.
                    Box(
                        Modifier.offset(x = with(density) { layout.x(index).toDp() } - 16.dp).width(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            minutes.toString(), fontFamily = Doto, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                            color = if (minutes == selected) c.head else c.muted
                        )
                    }
                }
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
            contentDescription = lead?.let { "Alarm ${minutesText(it)} before arrival" } ?: "No alarm time chosen"
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
