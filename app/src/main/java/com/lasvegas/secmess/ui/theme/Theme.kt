package com.lasvegas.secmess.ui.theme

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD4AF37),
    secondary = Color(0xFFFF0000),
    tertiary = Color(0xFFB8860B)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFD4AF37),
    secondary = Color(0xFFFF0000),
    tertiary = Color(0xFFB8860B)
)

@Composable
fun brandShimmerBrush(): Brush {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -500f,
        targetValue = 500f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )
    
    val goldColors = listOf(Color(0xFFD4AF37), Color(0xFFFFD700), Color(0xFFB8860B))
    val bloodColor = Color(0xFFFF0000)

    return Brush.linearGradient(
        colors = goldColors + listOf(bloodColor, Color(0xFF8B0000)),
        start = Offset(shimmerOffset, 0f),
        end = Offset(shimmerOffset + 200f, 200f)
    )
}

@Composable
fun SecMessTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
