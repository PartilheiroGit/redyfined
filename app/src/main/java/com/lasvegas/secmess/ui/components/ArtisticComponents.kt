package com.lasvegas.secmess.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lasvegas.secmess.R

@Composable
fun OGSecretBrandIcon(isConnected: Boolean, modifier: Modifier = Modifier) {
    val goldColors = listOf(Color(0xFFBF953F), Color(0xFFFCF6BA), Color(0xFFB38728))
    val bloodColors = listOf(Color(0xFF4A0000), Color(0xFF8B0000))
    
    Row(
        modifier = modifier.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Connectivity Dot (Next to icon)
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
        )
        
        Spacer(modifier = Modifier.width(8.dp))

        // Melted OG Brand
        Row(verticalAlignment = Alignment.Bottom) {
            // The O with Top Hat
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Stylish Top Hat
                Box(modifier = Modifier
                    .size(width = 16.dp, height = 10.dp)
                    .background(Color.Black, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                Box(modifier = Modifier
                    .size(width = 22.dp, height = 3.dp)
                    .background(Color.Black))
                // Melting O
                Text(
                    "O",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 32.sp,
                        brush = Brush.linearGradient(goldColors)
                    ),
                    modifier = Modifier.offset(y = (-4).dp)
                )
            }
            
            Spacer(modifier = Modifier.width(2.dp))
            
            // The G with Beard and Joint
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "G",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp,
                            brush = Brush.verticalGradient(bloodColors)
                        )
                    )
                    // Joint (Small white line with red tip)
                    Box(modifier = Modifier
                        .size(width = 10.dp, height = 2.dp)
                        .offset(x = 10.dp, y = 6.dp)
                        .background(Color.White)) {
                        Box(modifier = Modifier.size(2.dp).background(Color.Red).align(Alignment.CenterEnd))
                    }
                }
                // White Beard (Drip style)
                Box(modifier = Modifier
                    .size(width = 14.dp, height = 8.dp)
                    .background(Color.White, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    .offset(y = (-2).dp))
            }
        }
    }
}

@Composable
fun OnGoingTitle(text: String, modifier: Modifier = Modifier, isConnected: Boolean = false, onBannerClick: () -> Unit = {}) {
    val shimmerBrush = com.lasvegas.secmess.ui.theme.brandShimmerBrush()
    val bloodBrilliant = Color(0xFFFF0000) // Brilliant Red

    Column(
        modifier = modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onClick = onBannerClick
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectivityDot(isConnected = isConnected)
            Spacer(modifier = Modifier.width(8.dp))
            if (text.contains("Mess", ignoreCase = true)) {
                // BRAND: O-n G-oing Mess
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("O", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, fontWeight = FontWeight.Black, brush = shimmerBrush))
                        Text("-n ", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, brush = shimmerBrush))
                        Text("G", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, fontWeight = FontWeight.Black, brush = shimmerBrush))
                        Text("-oing", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, brush = shimmerBrush))
                    }
                    Box(modifier = Modifier.background(Color.Black, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp)) {
                        Text("Mess", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, color = bloodBrilliant))
                    }
                }
            } else {
                // BRAND: O-n G-oing CallEnder
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("O", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, fontWeight = FontWeight.Black, brush = shimmerBrush))
                        Text("-n ", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, brush = shimmerBrush))
                        Text("G", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, fontWeight = FontWeight.Black, brush = shimmerBrush))
                        Text("-oing", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, brush = shimmerBrush))
                    }
                    // Divided "CallEnder" Flag
                    Box(modifier = Modifier.padding(top = 2.dp).background(Color.Black, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Row {
                            Text("Call", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, brush = shimmerBrush))
                            Text("Ender", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, color = bloodBrilliant))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectivityDot(isConnected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(12.dp)
            .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
    )
}

@Composable
fun MarqueeSlogan(text: String, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "marquee")
    val xOffset by infiniteTransition.animateFloat(
        initialValue = 1.2f,
        targetValue = -1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "xOffset"
    )

    val goldColors = listOf(Color(0xFFBF953F), Color(0xFFFCF6BA), Color(0xFFB38728))
    val bloodColors = listOf(Color(0xFF4A0000), Color(0xFF8B0000))

    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(20.dp).background(Color.Black.copy(alpha = 0.05f))) {
        val width = constraints.maxWidth.toFloat()
        Text(
            text = text,
            modifier = Modifier.offset(x = (width * xOffset).dp / 3f),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                brush = Brush.linearGradient(colors = goldColors + bloodColors)
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun OpiarioExcerpt() {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
                )
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.poem_excerpt),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontStyle = FontStyle.Italic,
                lineHeight = 22.sp
            ),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.poem_source),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            modifier = Modifier.clickable {
                uriHandler.openUri("https://www.escritas.org/pt/t/4624/opiario")
            }
        )
    }
}

private val MaterialTheme.colorSurface: Color
    @Composable
    get() = colorScheme.onSurface

@Composable
fun AnimatedScrollIndicator(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "scroll")
        val translateY by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 10f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "translate"
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.offset(y = translateY.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.scroll_for_more),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun BrandPartnerPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OnGoingTitle(text = "O.n the G.o Callendar")
            Spacer(modifier = Modifier.height(32.dp))
            OpiarioExcerpt()
        }
    }
}
