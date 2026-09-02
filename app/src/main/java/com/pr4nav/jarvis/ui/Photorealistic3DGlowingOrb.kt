package com.pr4nav.jarvis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Static Photorealistic 3D Celestial Molten Lava Sphere
 * Pure vector rendering with fruity vibrant lava orange tones matching Dribbble Phone 2.
 */
@Composable
fun Photorealistic3DGlowingOrb(
    modifier: Modifier = Modifier,
    sizeDp: Dp = 210.dp
) {
    Box(
        modifier = modifier.size(sizeDp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2f, h / 2f)
            val radius = (minOf(w, h) / 2f) * 0.82f

            // 1. Soft atmospheric radiant glow behind sphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFF5722).copy(alpha = 0.55f),
                        Color(0xFFC62828).copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * 1.45f
                ),
                radius = radius * 1.45f,
                center = center
            )

            // Specular hotspot offset at top-left
            val specularCenter = Offset(center.x - radius * 0.32f, center.y - radius * 0.34f)

            // 2. 3D Spherical Volume Base with vivid fruity molten lava lighting
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to Color(0xFFFFFDE7), // Luminous specular light highlight
                        0.18f to Color(0xFFFFD54F), // Warm golden crest
                        0.42f to Color(0xFFFF6D00), // Vivid fruity citrus orange
                        0.68f to Color(0xFFFF3D00), // Electric lava mantle
                        0.85f to Color(0xFFC62828), // Deep crimson core
                        0.96f to Color(0xFF5D0000), // Volcanic ruby shadow
                        1.00f to Color(0xFF1E0000)  // Deep velvet limb
                    ),
                    center = specularCenter,
                    radius = radius * 1.30f
                ),
                radius = radius,
                center = center
            )

            // 3. Subtle warm rim reflection on opposite side
            val rimCenter = Offset(center.x + radius * 0.40f, center.y + radius * 0.40f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFFFF5722).copy(alpha = 0.25f),
                        Color(0xFFFFAB40).copy(alpha = 0.40f)
                    ),
                    center = rimCenter,
                    radius = radius * 0.85f
                ),
                radius = radius,
                center = center,
                blendMode = BlendMode.Screen
            )
        }
    }
}
