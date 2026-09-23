package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Centered NFC symbol surrounded by a satisfying 800ms concentric ripple animation
 * triggered upon successful NFC tag scan.
 *
 * Requirements met:
 * - Centered on NFC symbol
 * - White on AMOLED dark mode, dark on light mode
 * - 3 concentric expanding rings that fade out as they grow (sonar/water pulse)
 * - Lasts ~800 milliseconds and feels smooth and satisfying
 * - The NFC symbol itself remains completely static while the rings animate around it
 */
@Composable
fun NfcRippleSymbol(
    triggerKey: Long?,
    modifier: Modifier = Modifier,
    containerSize: Dp = 120.dp,
    iconSize: Dp = 36.dp,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    isDarkTheme: Boolean = isSystemInDarkTheme()
) {
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(triggerKey) {
        if (triggerKey != null && triggerKey > 0L) {
            animProgress.snapTo(0f)
            animProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 800,
                    easing = FastOutSlowInEasing
                )
            )
            // Reset to 0 when finished
            animProgress.snapTo(0f)
        }
    }

    val rippleColor = if (isDarkTheme) Color(0xFFFFFFFF) else Color(0xFF1F2024)

    Box(
        modifier = modifier
            .size(containerSize)
            .testTag("nfc_ripple_container"),
        contentAlignment = Alignment.Center
    ) {
        val currentProgress = animProgress.value

        if (currentProgress > 0f && currentProgress < 1f) {
            Canvas(
                modifier = Modifier
                    .size(containerSize)
                    .testTag("nfc_ripple_canvas")
            ) {
                val centerOffset = center
                val minR = (iconSize.toPx() / 2f) + 8.dp.toPx()
                val maxR = size.minDimension / 2f - 4.dp.toPx()
                val strokeWidth = 2.dp.toPx()

                // Ring 1 (t = 0.0)
                val p1 = currentProgress.coerceIn(0f, 1f)
                val r1 = minR + (maxR - minR) * p1
                val a1 = ((1f - p1) * 0.85f).coerceIn(0f, 1f)
                drawCircle(
                    color = rippleColor.copy(alpha = a1),
                    radius = r1,
                    center = centerOffset,
                    style = Stroke(width = strokeWidth)
                )

                // Ring 2 (t = 0.15)
                if (currentProgress >= 0.15f) {
                    val p2 = ((currentProgress - 0.15f) / 0.85f).coerceIn(0f, 1f)
                    val r2 = minR + (maxR - minR) * p2
                    val a2 = ((1f - p2) * 0.70f).coerceIn(0f, 1f)
                    drawCircle(
                        color = rippleColor.copy(alpha = a2),
                        radius = r2,
                        center = centerOffset,
                        style = Stroke(width = strokeWidth)
                    )
                }

                // Ring 3 (t = 0.30)
                if (currentProgress >= 0.30f) {
                    val p3 = ((currentProgress - 0.30f) / 0.70f).coerceIn(0f, 1f)
                    val r3 = minR + (maxR - minR) * p3
                    val a3 = ((1f - p3) * 0.55f).coerceIn(0f, 1f)
                    drawCircle(
                        color = rippleColor.copy(alpha = a3),
                        radius = r3,
                        center = centerOffset,
                        style = Stroke(width = strokeWidth)
                    )
                }
            }
        }

        // Static NFC symbol remains fixed in place
        Icon(
            imageVector = Icons.Default.Sensors,
            contentDescription = "NFC Scanner",
            tint = iconTint,
            modifier = Modifier
                .size(iconSize)
                .testTag("nfc_static_symbol")
        )
    }
}
