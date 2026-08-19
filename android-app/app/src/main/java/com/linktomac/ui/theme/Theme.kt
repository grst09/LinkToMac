package com.linktomac.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LinkToMacDarkColors = darkColorScheme(
    primary = LinkGreen,
    onPrimary = LinkGreenOn,
    primaryContainer = LinkGreenContainerDark,
    onPrimaryContainer = LinkGreenOnContainerDark,
    secondary = PureWhite, // the laptop's color in the dark-theme icon
    onSecondary = NearBlack,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = PureWhite,
    tertiary = LinkGreen,
    onTertiary = LinkGreenOn,
    background = NearBlack,
    onBackground = NearWhite,
    surface = DarkSurface,
    onSurface = NearWhite,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    // Card/Surface tonal elevation blends this into surface color — without an explicit
    // override it defaults to Material's baseline purple, which has nothing to do with the
    // app icon's palette.
    surfaceTint = LinkGreen,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceBrightDark,
    inverseSurface = NearWhite,
    inverseOnSurface = NearBlack,
    inversePrimary = Color(0xFF1B7A4A),
)

private val LinkToMacLightColors = lightColorScheme(
    primary = LinkGreen,
    onPrimary = LinkGreenOn,
    primaryContainer = LinkGreenContainerLight,
    onPrimaryContainer = LinkGreenOnContainerLight,
    secondary = Ink, // the laptop's color in the light-theme icon
    onSecondary = PureWhite,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = Ink,
    tertiary = LinkGreen,
    onTertiary = LinkGreenOn,
    background = NearWhite,
    onBackground = Ink,
    surface = PureWhite,
    onSurface = Ink,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    surfaceTint = LinkGreen,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
    inverseSurface = Ink,
    inverseOnSurface = NearWhite,
    inversePrimary = LinkGreen,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Resolves a user-selected [ThemeMode] against the current system setting — SYSTEM defers to
 *  [isSystemInDarkTheme], LIGHT/DARK pin it regardless of the device setting. */
@Composable
fun resolveDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** App-wide theme built from the app icon's palette (assets/app-icon/README.md) — brand green
 *  fixed across modes, neutrals following the icon's own dark/light inversion. */
@Composable
fun LinkToMacTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) LinkToMacDarkColors else LinkToMacLightColors,
        content = content
    )
}
