package com.nexus.controller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.protocol.Protocol
import com.nexus.protocol.RacingMath
import kotlin.math.abs
import kotlin.math.hypot

@Composable
fun rememberHaptic(enabled: Boolean): () -> Unit {
    val view = LocalView.current
    return remember(enabled, view) { { if (enabled) view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) } }
}

/** A momentary button. Releases on finger-up anywhere, on cancellation, and when the composable leaves the screen (finally block). */
@Composable
fun GameButton(
    label: String, modifier: Modifier = Modifier, diameter: Dp = 60.dp, shape: Shape = CircleShape, alpha: Float = 1f,
    haptic: () -> Unit = {}, onChange: (Boolean) -> Unit
) {
    var down by remember { mutableStateOf(false) }
    val cb by rememberUpdatedState(onChange)
    val hp by rememberUpdatedState(haptic)
    Box(
        modifier.size(diameter).alpha(alpha).clip(shape)
            .background(if (down) Accent else Panel).border(2.dp, if (down) Accent else AccentDim, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    down = true; hp(); cb(true)
                    try {
                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                    } finally { down = false; cb(false) }
                }
            },
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (down) Navy else TextMain, fontWeight = FontWeight.Bold, fontSize = (diameter.value * 0.3f).coerceIn(10f, 22f).sp) }
}

/** Rectangular button variant for shoulders / small system buttons. */
@Composable
fun PillButton(label: String, modifier: Modifier = Modifier, width: Dp = 72.dp, height: Dp = 34.dp, alpha: Float = 1f, haptic: () -> Unit = {}, onChange: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    val cb by rememberUpdatedState(onChange)
    val hp by rememberUpdatedState(haptic)
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.size(width, height).alpha(alpha).clip(shape).background(if (down) Accent else Panel).border(2.dp, if (down) Accent else AccentDim, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    down = true; hp(); cb(true)
                    try { do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed }) } finally { down = false; cb(false) }
                }
            },
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (down) Navy else TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}

/** Analog stick: tracks only the pointer that went down on it; returns to neutral on release/cancel/dispose. onMove(x, y) with y up. */
@Composable
fun AnalogStick(diameter: Dp, modifier: Modifier = Modifier, alpha: Float = 1f, haptic: () -> Unit = {}, onMove: (Float, Float) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val cb by rememberUpdatedState(onMove)
    val hp by rememberUpdatedState(haptic)
    Box(
        modifier.size(diameter).alpha(alpha).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                hp()
                val radius = size.width / 2f
                fun update(p: Offset) {
                    val d = Offset((p.x - radius) / radius, (p.y - radius) / radius)
                    val m = hypot(d.x, d.y)
                    val n = if (m > 1f) Offset(d.x / m, d.y / m) else d
                    knob = n; cb(n.x, -n.y)
                }
                update(down.position)
                try {
                    do {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id }
                        if (ch != null && ch.pressed) { update(ch.position); ch.consume() }
                    } while (e.changes.any { it.id == down.id && it.pressed })
                } finally { knob = Offset.Zero; cb(0f, 0f) }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.width / 2f
            drawCircle(Charcoal, r); drawCircle(AccentDim, r, style = Stroke(3f))
            drawCircle(if (knob == Offset.Zero) Panel else Accent.copy(alpha = 0.9f), r * 0.38f, Offset(r + knob.x * r * 0.62f, r + knob.y * r * 0.62f))
            drawCircle(Accent, r * 0.38f, Offset(r + knob.x * r * 0.62f, r + knob.y * r * 0.62f), style = Stroke(3f))
        }
    }
}

/** 8-way D-pad; sliding between arms is allowed. onChange receives Protocol.DPAD_* bits. */
@Composable
fun DPad(diameter: Dp, modifier: Modifier = Modifier, alpha: Float = 1f, haptic: () -> Unit = {}, onChange: (Int) -> Unit) {
    var bits by remember { mutableIntStateOf(0) }
    val cb by rememberUpdatedState(onChange)
    val hp by rememberUpdatedState(haptic)
    Box(
        modifier.size(diameter).alpha(alpha).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val r = size.width / 2f
                fun update(p: Offset) {
                    val dx = p.x - r; val dy = p.y - r; val m = hypot(dx, dy)
                    var b = 0
                    if (m > r * 0.18f) {
                        val ux = dx / m; val uy = dy / m
                        if (ux > 0.38f) b = b or Protocol.DPAD_RIGHT; if (ux < -0.38f) b = b or Protocol.DPAD_LEFT
                        if (uy > 0.38f) b = b or Protocol.DPAD_DOWN; if (uy < -0.38f) b = b or Protocol.DPAD_UP
                    }
                    if (b != bits) { if (b != 0) hp(); bits = b; cb(b) }
                }
                update(down.position)
                try {
                    do {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id }
                        if (ch != null && ch.pressed) { update(ch.position); ch.consume() }
                    } while (e.changes.any { it.id == down.id && it.pressed })
                } finally { bits = 0; cb(0) }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.width / 2f; val t = r * 0.62f; val l = r * 0.98f
            fun arm(active: Boolean, topLeft: Offset, sz: Size) {
                drawRoundRect(if (active) Accent else Panel, topLeft, sz, androidx.compose.ui.geometry.CornerRadius(14f, 14f))
                drawRoundRect(if (active) Accent else AccentDim, topLeft, sz, androidx.compose.ui.geometry.CornerRadius(14f, 14f), style = Stroke(3f))
            }
            arm(bits and Protocol.DPAD_UP != 0, Offset(r - t / 2, r - l), Size(t, l - t * 0.2f))
            arm(bits and Protocol.DPAD_DOWN != 0, Offset(r - t / 2, r + t * 0.2f), Size(t, l - t * 0.2f))
            arm(bits and Protocol.DPAD_LEFT != 0, Offset(r - l, r - t / 2), Size(l - t * 0.2f, t))
            arm(bits and Protocol.DPAD_RIGHT != 0, Offset(r + t * 0.2f, r - t / 2), Size(l - t * 0.2f, t))
        }
    }
}

/** Analog bar for triggers/pedals. Value is the touch position along the bar (bottom = 0, top = 1); releases to 0. */
@Composable
fun AnalogBar(label: String, width: Dp, height: Dp, modifier: Modifier = Modifier, alpha: Float = 1f, haptic: () -> Unit = {}, onValue: (Float) -> Unit) {
    var value by remember { mutableFloatStateOf(0f) }
    val cb by rememberUpdatedState(onValue)
    val hp by rememberUpdatedState(haptic)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.size(width, height).alpha(alpha).clip(shape).background(Panel).border(2.dp, AccentDim, shape).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                hp()
                fun update(p: Offset) { val v = (1f - p.y / size.height).coerceIn(0f, 1f); value = v; cb(v) }
                update(down.position)
                try {
                    do {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id }
                        if (ch != null && ch.pressed) { update(ch.position); ch.consume() }
                    } while (e.changes.any { it.id == down.id && it.pressed })
                } finally { value = 0f; cb(0f) }
            }
        },
        contentAlignment = Alignment.TopCenter
    ) {
        Canvas(Modifier.fillMaxSize()) { drawRect(Accent.copy(alpha = 0.55f), Offset(0f, size.height * (1f - value)), Size(size.width, size.height * value)) }
        Text(label, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
    }
}

/** Virtual steering wheel: drag horizontally; springs back to centre at [returnSpeed] units/s when released. */
@Composable
fun SteeringWheel(width: Dp, height: Dp, returnSpeed: Float, modifier: Modifier = Modifier, alpha: Float = 1f, haptic: () -> Unit = {}, onValue: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    val cb by rememberUpdatedState(onValue)
    val hp by rememberUpdatedState(haptic)
    LaunchedEffect(holding, returnSpeed) {
        if (!holding) {
            var last = withFrameNanos { it }
            while (v != 0f) {
                val now = withFrameNanos { it }
                v = RacingMath.returnToCenter(v, returnSpeed, (now - last) / 1_000_000_000f); last = now
                cb(v)
            }
            cb(0f)
        }
    }
    Box(
        modifier.size(width, height).alpha(alpha).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                hp(); holding = true
                fun update(p: Offset) { val n = ((p.x / size.width) * 2f - 1f).coerceIn(-1f, 1f); v = n; cb(n) }
                update(down.position)
                try {
                    do {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id }
                        if (ch != null && ch.pressed) { update(ch.position); ch.consume() }
                    } while (e.changes.any { it.id == down.id && it.pressed })
                } finally { holding = false }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val d = minOf(size.width, size.height) * 0.92f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawRoundRect(Charcoal, Offset.Zero, size, androidx.compose.ui.geometry.CornerRadius(24f, 24f))
            drawLine(AccentDim, Offset(size.width / 2f, 6f), Offset(size.width / 2f, 22f), 4f)
            rotate(v * 110f, c) {
                drawCircle(Panel, d / 2f, c); drawCircle(Accent, d / 2f, c, style = Stroke(10f))
                drawLine(Accent, Offset(c.x - d / 2f, c.y), Offset(c.x + d / 2f, c.y), 8f)
                drawLine(Accent, c, Offset(c.x, c.y + d / 2f), 8f)
                drawCircle(Accent, d * 0.1f, c)
                drawLine(Danger, Offset(c.x, c.y - d / 2f), Offset(c.x, c.y - d / 2f + 22f), 8f)
            }
        }
    }
}

fun nearZero(v: Float) = abs(v) < 0.001f
