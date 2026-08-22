package com.linktomac.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Corner-radius scale for Cards, dialogs, and other Material3 components that read their shape
 *  from `MaterialTheme.shapes` rather than a per-call override. Pill-shaped controls (buttons,
 *  status chips) don't use this — they stay on an explicit `RoundedCornerShape(50)`, the
 *  convention `StatusPill` already established, since Material3's `Shapes()` has no dedicated
 *  pill slot. */
val LinkToMacShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
