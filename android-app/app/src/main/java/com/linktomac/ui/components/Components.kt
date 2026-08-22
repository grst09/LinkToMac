package com.linktomac.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Shared building blocks the redesign introduces so every screen restyles once and inherits it,
 * rather than each screen carrying its own inline `Card`/`Button` styling (see
 * `ui/theme/Shape.kt` for the corner-radius tokens these read from).
 */

/** Every `Card` in the app previously had two problems: 0dp elevation (Material3's default for a
 *  plain `Card`), and — the bigger one — no explicit container color, so it defaulted to
 *  `colorScheme.surfaceContainer` (`#EFEFEF` in light mode), which is barely distinguishable from
 *  the `#F2F2F2` page background. The reference design's cards are crisply *white* against a gray
 *  canvas; this is what actually makes that look happen — every other visual change (shape,
 *  elevation, pill buttons) was riding on top of cards that were nearly invisible against their
 *  own background before this. */
@Composable
fun LinkCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            colors = colors,
            elevation = elevation,
            content = content,
        )
    } else {
        Card(
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            colors = colors,
            elevation = elevation,
            content = content,
        )
    }
}

/** Fully-rounded primary action button — the reference design's CTA pill shape. */
@Composable
fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = colors,
        content = content,
    )
}

/** Same pill shape as [PillButton], outlined instead of filled — for secondary actions. */
@Composable
fun PillOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = colors,
        content = content,
    )
}

/** Small rounded label chip — the reference design's "14 Years expertise" / "96%" badges under a
 *  list row's title. */
@Composable
fun BadgePill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Compact branding header — circular tinted icon badge + title + optional subtitle + optional
 *  trailing slot. Left-aligned rather than centered, so real content can start right below it
 *  instead of the icon/title eating the top third of the screen. */
@Composable
fun AppHeader(
    leadingIcon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) trailing()
    }
}

/** One connected capsule split into equal-width segments, with a green highlight that slides
 *  smoothly to whichever one is selected — rather than Material3's own `SegmentedButton`, which
 *  just flips each segment's background color individually with no shared element animating
 *  between them, reading as an abrupt switch instead of a smooth one. */
@Composable
fun <T> SlidingSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
) {
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)

    // BoxWithConstraints, not Modifier.onSizeChanged + remembered state — the latter reports its
    // first real width one frame late (async layout callback), during which a conditionally-
    // composed highlight either doesn't exist yet or animates from a stale/zero width; the
    // symptom was the highlight silently failing to render after certain recompositions (e.g.
    // switching Dark/Light, which restyles this whole tree). `maxWidth` here is available
    // synchronously at composition time, so the highlight is correctly sized from frame one.
    //
    // Height is an explicit fixed value rather than IntrinsicSize.Min (which would let
    // fillMaxHeight() below derive a height from the Row's own content) — BoxWithConstraints is
    // built on SubcomposeLayout, and Compose does not support intrinsic measurement of
    // SubcomposeLayout at all; asking for it throws IllegalStateException at runtime (a real
    // crash hit while testing this, not a theoretical concern).
    val height = 48.dp
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp)
    ) {
        val segmentWidth = maxWidth / options.size.coerceAtLeast(1)
        val animatedOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "segmentOffset",
        )

        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary)
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEach { option ->
                val isSelected = option == selected
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "segmentContentColor",
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .selectable(selected = isSelected, onClick = { onSelect(option) }, role = Role.RadioButton),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Icon(icon(option), contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(label(option), color = contentColor, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Icon-badge header + a short bullet list — the reference design's "AI Summary"/"AI Key Points"
 *  card anatomy. Applied to a real use here: breaking a permission ask down into the specific
 *  things it's used for, instead of one dense paragraph. */
@Composable
fun InfoCard(
    icon: ImageVector,
    title: String,
    bullets: List<String>,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    LinkCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                bullets.forEach { bullet ->
                    Row {
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(bullet, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (footer != null) {
                Spacer(Modifier.height(12.dp))
                footer()
            }
        }
    }
}

/** List-row anatomy: circular avatar/icon, title/subtitle column, optional badge-pill row under
 *  the subtitle, an optional top-aligned trailing slot (e.g. a chevron), and an optional extra
 *  content slot below the row (e.g. an action-button row) — the reference design's "Top Doctors"
 *  row shape, applied to whatever LinkToMac actually has rows of (paired devices, sync options). */
@Composable
fun ListItemCard(
    leading: @Composable () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    badges: List<String> = emptyList(),
    trailingTop: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    LinkCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leading()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
                    }
                    if (badges.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            badges.forEach { BadgePill(it) }
                        }
                    }
                }
                if (trailingTop != null) trailingTop()
            }
            if (content != null) {
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}
