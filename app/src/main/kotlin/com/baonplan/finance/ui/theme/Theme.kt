package com.baonplan.finance.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.baonplan.finance.domain.model.ThemeMode

@Immutable
data class BaonSemanticColors(
  val positive: Color,
  val positiveContainer: Color,
  val onPositiveContainer: Color,
  val warning: Color,
  val warningContainer: Color,
  val onWarningContainer: Color,
  val danger: Color,
  val dangerContainer: Color,
  val onDangerContainer: Color,
  val muted: Color,
  val divider: Color,
  val chartPalette: List<Color>
)

private val LightSemanticColors = BaonSemanticColors(
  positive = Color(0xFF14796F),
  positiveContainer = Color(0xFFE0F1EE),
  onPositiveContainer = Color(0xFF0A3A35),
  warning = Color(0xFF98601C),
  warningContainer = Color(0xFFFAF0E0),
  onWarningContainer = Color(0xFF4A2E09),
  danger = Color(0xFFB33A34),
  dangerContainer = Color(0xFFFCE9E8),
  onDangerContainer = Color(0xFF4F1310),
  muted = Color(0xFF6B7178),
  divider = Color(0xFFE5E3DD),
  chartPalette = listOf(
    Color(0xFF0F766E),
    Color(0xFF3B6E8C),
    Color(0xFF6E8B74),
    Color(0xFFB87D3B),
    Color(0xFF7B8591)
  )
)

private val DarkSemanticColors = BaonSemanticColors(
  positive = Color(0xFF5BB2A8),
  positiveContainer = Color(0xFF193834),
  onPositiveContainer = Color(0xFFCBECE8),
  warning = Color(0xFFD7A35E),
  warningContainer = Color(0xFF392B19),
  onWarningContainer = Color(0xFFF5DFBF),
  danger = Color(0xFFE0857F),
  dangerContainer = Color(0xFF3F201E),
  onDangerContainer = Color(0xFFF7D6D4),
  muted = Color(0xFF969DA6),
  divider = Color(0xFF2D333A),
  chartPalette = listOf(
    Color(0xFF5BB2A8),
    Color(0xFF6BA0BE),
    Color(0xFF83A88B),
    Color(0xFFD7A35E),
    Color(0xFF8A949E)
  )
)

val LocalBaonColors = staticCompositionLocalOf { LightSemanticColors }

private val LightColorScheme = lightColorScheme(
  primary = PrimaryLight,
  onPrimary = OnPrimaryLight,
  primaryContainer = PrimaryContainerLight,
  onPrimaryContainer = OnPrimaryContainerLight,
  secondary = SecondaryLight,
  onSecondary = OnSecondaryLight,
  secondaryContainer = SecondaryContainerLight,
  onSecondaryContainer = OnSecondaryContainerLight,
  tertiary = TertiaryLight,
  onTertiary = OnTertiaryLight,
  tertiaryContainer = TertiaryContainerLight,
  onTertiaryContainer = OnTertiaryContainerLight,
  error = ErrorLight,
  onError = OnErrorLight,
  errorContainer = ErrorContainerLight,
  onErrorContainer = OnErrorContainerLight,
  background = BackgroundLight,
  onBackground = OnBackgroundLight,
  surface = SurfaceLight,
  onSurface = OnSurfaceLight,
  surfaceVariant = SurfaceVariantLight,
  onSurfaceVariant = OnSurfaceVariantLight,
  outline = OutlineLight,
  outlineVariant = OutlineVariantLight,
  surfaceContainerLowest = SurfaceContainerLowestLight,
  surfaceContainerLow = SurfaceContainerLowLight,
  surfaceContainer = SurfaceContainerLight,
  surfaceContainerHigh = SurfaceContainerHighLight,
  surfaceContainerHighest = SurfaceContainerHighestLight
)

private val DarkColorScheme = darkColorScheme(
  primary = PrimaryDark,
  onPrimary = OnPrimaryDark,
  primaryContainer = PrimaryContainerDark,
  onPrimaryContainer = OnPrimaryContainerDark,
  secondary = SecondaryDark,
  onSecondary = OnSecondaryDark,
  secondaryContainer = SecondaryContainerDark,
  onSecondaryContainer = OnSecondaryContainerDark,
  tertiary = TertiaryDark,
  onTertiary = OnTertiaryDark,
  tertiaryContainer = TertiaryContainerDark,
  onTertiaryContainer = OnTertiaryContainerDark,
  error = ErrorDark,
  onError = OnErrorDark,
  errorContainer = ErrorContainerDark,
  onErrorContainer = OnErrorContainerDark,
  background = BackgroundDark,
  onBackground = OnBackgroundDark,
  surface = SurfaceDark,
  onSurface = OnSurfaceDark,
  surfaceVariant = SurfaceVariantDark,
  onSurfaceVariant = OnSurfaceVariantDark,
  outline = OutlineDark,
  outlineVariant = OutlineVariantDark,
  surfaceContainerLowest = SurfaceContainerLowestDark,
  surfaceContainerLow = SurfaceContainerLowDark,
  surfaceContainer = SurfaceContainerDark,
  surfaceContainerHigh = SurfaceContainerHighDark,
  surfaceContainerHighest = SurfaceContainerHighestDark
)

@Composable
private fun animateSchemeColor(target: Color): Color {
  val animated by animateColorAsState(
    targetValue = target,
    animationSpec = tween(durationMillis = 220),
    label = "themeColor"
  )
  return animated
}

@Composable
private fun animatedColorScheme(target: ColorScheme): ColorScheme = target.copy(
  primary = animateSchemeColor(target.primary),
  onPrimary = animateSchemeColor(target.onPrimary),
  primaryContainer = animateSchemeColor(target.primaryContainer),
  onPrimaryContainer = animateSchemeColor(target.onPrimaryContainer),
  inversePrimary = animateSchemeColor(target.inversePrimary),
  secondary = animateSchemeColor(target.secondary),
  onSecondary = animateSchemeColor(target.onSecondary),
  secondaryContainer = animateSchemeColor(target.secondaryContainer),
  onSecondaryContainer = animateSchemeColor(target.onSecondaryContainer),
  tertiary = animateSchemeColor(target.tertiary),
  onTertiary = animateSchemeColor(target.onTertiary),
  tertiaryContainer = animateSchemeColor(target.tertiaryContainer),
  onTertiaryContainer = animateSchemeColor(target.onTertiaryContainer),
  background = animateSchemeColor(target.background),
  onBackground = animateSchemeColor(target.onBackground),
  surface = animateSchemeColor(target.surface),
  onSurface = animateSchemeColor(target.onSurface),
  surfaceVariant = animateSchemeColor(target.surfaceVariant),
  onSurfaceVariant = animateSchemeColor(target.onSurfaceVariant),
  surfaceTint = animateSchemeColor(target.surfaceTint),
  inverseSurface = animateSchemeColor(target.inverseSurface),
  inverseOnSurface = animateSchemeColor(target.inverseOnSurface),
  error = animateSchemeColor(target.error),
  onError = animateSchemeColor(target.onError),
  errorContainer = animateSchemeColor(target.errorContainer),
  onErrorContainer = animateSchemeColor(target.onErrorContainer),
  outline = animateSchemeColor(target.outline),
  outlineVariant = animateSchemeColor(target.outlineVariant),
  scrim = animateSchemeColor(target.scrim),
  surfaceBright = animateSchemeColor(target.surfaceBright),
  surfaceDim = animateSchemeColor(target.surfaceDim),
  surfaceContainer = animateSchemeColor(target.surfaceContainer),
  surfaceContainerHigh = animateSchemeColor(target.surfaceContainerHigh),
  surfaceContainerHighest = animateSchemeColor(target.surfaceContainerHighest),
  surfaceContainerLow = animateSchemeColor(target.surfaceContainerLow),
  surfaceContainerLowest = animateSchemeColor(target.surfaceContainerLowest)
)

@Composable
fun BaonPlanTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  content: @Composable () -> Unit
) {
  val useDarkTheme = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
  }

  val targetScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme
  val colorScheme = animatedColorScheme(targetScheme)
  val semanticColors = if (useDarkTheme) DarkSemanticColors else LightSemanticColors

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window
      if (window != null) {
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.isAppearanceLightStatusBars = !useDarkTheme
        insetsController.isAppearanceLightNavigationBars = !useDarkTheme
      }
    }
  }

  CompositionLocalProvider(LocalBaonColors provides semanticColors) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = BaonTypography,
      content = content
    )
  }
}
