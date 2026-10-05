package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Waveform display for the HUD.
 *
 * The animated and idle variants are separate composables on purpose. An earlier version called
 * `rememberInfiniteTransition` unconditionally, which drove a frame loop and rebuilt two Paths 60
 * times a second for the entire time the app was open, including when the session was idle. On a
 * 3 GB device that is a standing CPU tax for a decoration the user is not even looking at.
 * Splitting the call sites lets the transition be created only while audio is actually flowing.
 */
@Composable
fun ReactiveWaveform(
    amplitude: Float,
    isSpeaking: Boolean,
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    if (isSpeaking || isListening) {
        AnimatedReactiveWaveform(
            amplitude = amplitude,
            isSpeaking = isSpeaking,
            modifier = modifier
        )
    } else {
        IdleReactiveWaveform(modifier = modifier)
    }
}

@Composable
private fun AnimatedReactiveWaveform(
    amplitude: Float,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val accentColor =
        if (isSpeaking) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    val transition = rememberInfiniteTransition(label = "waveform_phase")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        drawWaveform(
            amplitude = amplitude,
            phase = phase,
            primaryColor = primaryColor,
            accentColor = accentColor
        )
    }
}

/**
 * Static flat line for an idle session. No transition, no allocation, no frame callbacks.
 */
@Composable
private fun IdleReactiveWaveform(modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        val centerY = size.height / 2f
        drawLine(
            color = primaryColor.copy(alpha = 0.25f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1.dp.toPx()
        )
    }
}

/**
 * Draws the two-harmonic wave. `phase` is the only animated input; amplitude and colours are
 * captured per frame.
 *
 * Stepping 6px instead of 4px cuts the point count by a third. At this canvas height the two are
 * visually indistinguishable and the saving matters more than the extra vertex.
 */
private fun DrawScope.drawWaveform(
    amplitude: Float,
    phase: Float,
    primaryColor: Color,
    accentColor: Color
) {
    val width = size.width
    val height = size.height
    val centerY = height / 2f

    val effectiveAmp = amplitude.coerceIn(0.05f, 1f) * (height * 0.32f)

    // Base horizontal centerline glow
    drawLine(
        color = primaryColor.copy(alpha = 0.25f),
        start = Offset(0f, centerY),
        end = Offset(width, centerY),
        strokeWidth = 1.dp.toPx()
    )

    val step = 6f

    // Wave 1: Primary harmonic
    val path1 = Path()
    path1.moveTo(0f, centerY)
    var x = 0f
    while (x <= width) {
        val envelope = sin((x / width) * PI).toFloat() // window envelope, zero at both edges
        path1.lineTo(x, centerY + sin(x * 0.035f + phase).toFloat() * effectiveAmp * envelope)
        x += step
    }

    drawPath(
        path = path1,
        color = accentColor,
        style = Stroke(width = 2.5.dp.toPx())
    )

    // Wave 2: Secondary harmonic
    val path2 = Path()
    path2.moveTo(0f, centerY)
    x = 0f
    while (x <= width) {
        val envelope = sin((x / width) * PI).toFloat()
        path2.lineTo(
            x,
            centerY + sin(x * 0.05f - phase * 1.3f).toFloat() * (effectiveAmp * 0.65f) * envelope
        )
        x += step
    }

    drawPath(
        path = path2,
        color = primaryColor.copy(alpha = 0.6f),
        style = Stroke(width = 1.8.dp.toPx())
    )
}