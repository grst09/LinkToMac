package com.linktomac.ui.theme

import androidx.compose.ui.graphics.Color

// Brand green — the phone shape in the app icon (assets/app-icon/*.svg). Stays fixed across
// both themes; only the neutrals around it invert, same as the icon itself.
val LinkGreen = Color(0xFF3DDC84)
val LinkGreenOn = Color(0xFF0A0A0A)
val LinkGreenContainerLight = Color(0xFFB6F2D3)
val LinkGreenOnContainerLight = Color(0xFF0A3320)
val LinkGreenContainerDark = Color(0xFF1B4A30)
val LinkGreenOnContainerDark = Color(0xFFB6F2D3)

// Neutral scale lifted straight from the icon's canvas/laptop colors.
val NearBlack = Color(0xFF0A0A0A) // dark-theme canvas
val NearWhite = Color(0xFFF2F2F2) // light-theme canvas
val Ink = Color(0xFF1A1A1A) // light-theme laptop / light-theme text
val PureWhite = Color(0xFFFFFFFF) // dark-theme laptop / light-theme surface

val DarkSurface = Color(0xFF141414)
val DarkSurfaceVariant = Color(0xFF1F1F1F)
val DarkOutline = Color(0xFF3A3A3A)
val DarkOnSurfaceVariant = Color(0xFFBFBFBF)

val LightSurfaceVariant = Color(0xFFE4E4E4)
val LightOutline = Color(0xFFCFCFCF)
val LightOnSurfaceVariant = Color(0xFF4A4A4A)

val ErrorLight = Color(0xFFC62828)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD4)
val OnErrorContainerLight = Color(0xFF410001)

val ErrorDark = Color(0xFFFF6E5C)
val OnErrorDark = Color(0xFF3A0A05)
val ErrorContainerDark = Color(0xFF7A281D)
val OnErrorContainerDark = Color(0xFFFFDAD4)

// Material3's color-scheme builders default every surfaceContainer*/inverse* role to a tone
// computed from a baked-in purple seed when it isn't passed explicitly — Card, NavigationBar,
// BottomSheet etc. all read those roles by default, which is what caused the lavender tint on
// cards. Every role below is set so nothing falls back to that default.
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF7F7F7)
val SurfaceContainerLight = Color(0xFFEFEFEF)
val SurfaceContainerHighLight = Color(0xFFE6E6E6)
val SurfaceContainerHighestLight = Color(0xFFDDDDDD)
val SurfaceDimLight = Color(0xFFD9D9D9)
val SurfaceBrightLight = Color(0xFFFFFFFF)

val SurfaceContainerLowestDark = Color(0xFF060606)
val SurfaceContainerLowDark = Color(0xFF121212)
val SurfaceContainerDark = Color(0xFF1A1A1A)
val SurfaceContainerHighDark = Color(0xFF232323)
val SurfaceContainerHighestDark = Color(0xFF2D2D2D)
val SurfaceDimDark = Color(0xFF0A0A0A)
val SurfaceBrightDark = Color(0xFF2B2B2B)

// Decorative accents for quick-action tiles only — not used as core Material color roles.
val AccentBlue = Color(0xFF4C8DFF)
val AccentBlueContainerLight = Color(0xFFDCE7FF)
val AccentBlueContainerDark = Color(0xFF1B3560)
val AccentViolet = Color(0xFFB07CFF)
val AccentVioletContainerLight = Color(0xFFECDCFF)
val AccentVioletContainerDark = Color(0xFF3B2360)
