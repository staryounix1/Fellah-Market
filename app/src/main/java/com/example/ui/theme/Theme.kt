package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val FellahLightColorScheme = lightColorScheme(
    primary = ForestGreen,
    onPrimary = CrispSurface,
    primaryContainer = SoftSageContainer,
    onPrimaryContainer = OnSageContainer,
    secondary = HarvestTerracotta,
    onSecondary = CrispSurface,
    secondaryContainer = WarmApricotContainer,
    onSecondaryContainer = OnApricotContainer,
    tertiary = SaffronGold,
    onTertiary = CrispSurface,
    tertiaryContainer = SaffronContainer,
    onTertiaryContainer = OnSaffronContainer,
    background = WarmParchment,
    onBackground = InkCharcoal,
    surface = CrispSurface,
    onSurface = InkCharcoal,
    surfaceVariant = SoftFieldSurface,
    onSurfaceVariant = MutedMossText,
    outline = SubtleBorder,
    outlineVariant = SubtleBorder,
    error = ErrorCrimson,
    onError = CrispSurface,
    errorContainer = ErrorContainerLight,
    onErrorContainer = ErrorCrimson
)

val FellahShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun FellahMarketTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FellahLightColorScheme,
        typography = AppTypography,
        shapes = FellahShapes,
        content = content
    )
}
