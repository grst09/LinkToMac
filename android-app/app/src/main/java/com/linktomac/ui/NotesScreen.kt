package com.linktomac.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linktomac.net.NoteEntry
import com.linktomac.ui.theme.AccentYellow
import com.linktomac.ui.theme.AccentYellowOn
import com.linktomac.storage.NoteStore
import com.linktomac.ui.components.AppHeader
import com.linktomac.ui.components.LinkCard
import java.text.DateFormat
import java.util.Date

/**
 * Notes list + create/edit/delete, all local to the phone (see docs/PROTOCOL.md's Phase 8 notes
 * on why there's no system provider to mirror here). Every mutation calls [onChanged] so the
 * caller can push a fresh `notes.sync` to the Mac — this screen doesn't touch [MacConnection]
 * itself, same separation PairingScreen keeps from SyncForegroundService.
 *
 * A two-column card grid with a pinned section, search, and a FAB for new notes — restyled onto
 * the app's shared design system (`AppHeader`, `LinkCard`, the brand-green + neutral palette from
 * `ui/theme`) instead of the ad hoc pastel-per-card look it originally shipped with, which read as
 * a different app bolted onto the rest of LinkToMac.
 */
@Composable
fun NotesScreen(
    noteStore: NoteStore,
    onChanged: () -> Unit,
    onSyncRequested: () -> Unit,
    initialListPaneWidthDp: Float,
    onListPaneWidthChanged: (Float) -> Unit,
) {
    var notes by remember { mutableStateOf(noteStore.readAll()) }
    var editing by remember { mutableStateOf<NoteEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    fun refresh() {
        notes = noteStore.readAll()
    }

    val showingEditor = creating || editing != null
    // Only meaningful in the list-detail layout below — collapsed while editing, hiding the list
    // pane so the editor (and its toolbar) gets the full pane width instead of whatever's left
    // after dragging the divider. Resets once the editor closes so the list is never left hidden
    // with nothing on screen able to bring it back.
    var listPaneCollapsed by remember { mutableStateOf(false) }
    LaunchedEffect(showingEditor) {
        if (!showingEditor) listPaneCollapsed = false
    }
    val editor: @Composable (showCollapseToggle: Boolean) -> Unit = { showCollapseToggle ->
        NoteEditor(
            existing = editing,
            onCancel = {
                creating = false
                editing = null
            },
            onSave = { title, body ->
                val current = editing
                if (current != null) {
                    noteStore.update(current.id, title, body)
                } else {
                    noteStore.create(title, body)
                }
                onChanged()
                refresh()
                creating = false
                editing = null
            },
            onDelete = editing?.let { note ->
                {
                    noteStore.delete(note.id)
                    onChanged()
                    refresh()
                    editing = null
                }
            },
            onTogglePin = editing?.let { note ->
                {
                    noteStore.setPinned(note.id, !note.isPinned)
                    onChanged()
                    refresh()
                    editing = notes.find { it.id == note.id }
                }
            },
            isListPaneCollapsed = listPaneCollapsed,
            onToggleListPaneCollapsed = if (showCollapseToggle) {
                { listPaneCollapsed = !listPaneCollapsed }
            } else {
                null
            }
        )
    }
    val grid: @Composable (singleColumn: Boolean, onNoteClick: (NoteEntry) -> Unit) -> Unit = { singleColumn, onNoteClick ->
        NotesGrid(
            notes = notes,
            searchActive = searchActive,
            onSearchActiveChanged = { searchActive = it },
            searchQuery = searchQuery,
            onSearchQueryChanged = { searchQuery = it },
            onSyncRequested = onSyncRequested,
            onNoteClick = onNoteClick,
            onCreateClick = { creating = true },
            selectedNoteId = editing?.id,
            singleColumn = singleColumn
        )
    }

    // List-detail on a wide window (the Fold unfolded, a tablet, a desktop window, …) — list and
    // detail panes show side by side, per the list-detail canonical layout recommended at
    // https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps,
    // rather than a full-screen swap that leaves all the extra width doing nothing.
    //
    // The guide's own line is "2+ panes only once a window is genuinely Expanded" (840dp+), but
    // this app's actual target foldable (a Z Fold–class device) unfolds to ~752dp — comfortably
    // Medium (600–840dp) by that strict definition, never Expanded. Gating on 840dp would mean
    // the unfolded screen — the entire point of "I have two different screen sizes to use" — never
    // gets the two-pane layout at all. 600dp (Medium's own lower bound) is what actually lets this
    // device's second screen size do something a phone-sized window can't, while a genuinely
    // Compact window (~360dp, this same device folded) still safely falls through to the original
    // single-pane behavior below. Read directly off the configuration rather than through the
    // adaptive library's own WindowSizeClass helper, whose exact breakpoint constants/methods vary
    // across the androidx.window versions different Compose BOMs happen to pull in transitively.
    val useListDetailLayout = LocalConfiguration.current.screenWidthDp >= 600

    if (useListDetailLayout) {
        // The list pane defaults to a single column (vertical list) here rather than the 2-column
        // grid the compact/full-screen case below uses — that grid shape only exists to fill a
        // full-width screen usefully; once the detail pane is taking most of the width, a narrower
        // single-column list reads better and leaves more room for it. Its width is user-resizable
        // (drag the divider) since there's no one "right" split between browsing notes and reading/
        // editing one — bounded so neither pane can be dragged out of usability.
        val screenWidthDp = LocalConfiguration.current.screenWidthDp
        val minListPaneWidthDp = 240f
        // The detail pane's own top bar is three pills — Back, the (horizontally scrollable)
        // formatting toolbar, Pin/Delete — and below ~380dp the fixed-width Back and Pin/Delete
        // pills alone eat the row, leaving the formatting pill's `weight(1f)` a sliver too thin to
        // render at all rather than just cramped. 380dp keeps a real minimum width for it. The
        // divider itself (see ResizableDivider) is also 16dp wide and sits between the two panes,
        // so it has to come out of the same budget or dragging to the nominal limit would still
        // squeeze the detail pane 16dp tighter than intended.
        val minEditPaneWidthDp = 380f
        val dividerWidthDp = 16f
        val maxListPaneWidthDp = (screenWidthDp - minEditPaneWidthDp - dividerWidthDp).coerceAtLeast(minListPaneWidthDp)
        // Seeded from the persisted value (300dp default — see AppSettingsStore) rather than
        // always starting fresh, so a drag actually sticks as "how I like it" instead of
        // resetting the next time the note editor opens.
        var listPaneWidthDp by remember { mutableFloatStateOf(initialListPaneWidthDp) }
        val clampedListPaneWidthDp = listPaneWidthDp.coerceIn(minListPaneWidthDp, maxListPaneWidthDp)

        Row(modifier = Modifier.fillMaxSize()) {
            if (!listPaneCollapsed) {
                // A shade darker than the detail pane (surfaceContainer vs. the page background) —
                // a real panel boundary, not just the thin divider line between two otherwise
                // identical-looking areas.
                Box(
                    modifier = Modifier
                        .width(clampedListPaneWidthDp.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    grid(true) { note -> editing = note; creating = false }
                }
                ResizableDivider(
                    onDragDp = { deltaDp ->
                        listPaneWidthDp = (listPaneWidthDp + deltaDp).coerceIn(minListPaneWidthDp, maxListPaneWidthDp)
                        onListPaneWidthChanged(listPaneWidthDp)
                    }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                if (showingEditor) {
                    editor(true)
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Filled.StickyNote2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Select a note, or create a new one",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    } else {
        // Opening/closing a note used to be an instant hard cut between the grid and the editor —
        // this crossfades with a slight scale, like the note "lifting off" the grid on the way in
        // and settling back into it on the way out, instead of just snapping between two screens.
        AnimatedContent(
            targetState = showingEditor,
            transitionSpec = {
                val opening = targetState
                (fadeIn(tween(220)) + scaleIn(initialScale = if (opening) 0.94f else 1.05f, animationSpec = tween(220)))
                    .togetherWith(fadeOut(tween(160)) + scaleOut(targetScale = if (opening) 1.05f else 0.94f, animationSpec = tween(160)))
                    .using(SizeTransform(clip = false))
            },
            label = "note-detail-transition"
        ) { showing ->
            if (showing) editor(false) else grid(false) { note -> editing = note }
        }
    }
}

/** The notes grid: header (title/count, or search when active), the pinned/others grid itself,
 *  and the "new note" FAB. Shared between the compact single-pane layout (the whole screen, always
 *  2 columns) and the expanded list-detail layout (just the list pane, [singleColumn] — see
 *  [NotesScreen], which also sizes and resizes that pane). [selectedNoteId] highlights the
 *  currently-open note in list-detail mode; it's always null in single-pane mode, since there's no
 *  "currently open" note to distinguish once opening one replaces the grid outright. */
@Composable
private fun NotesGrid(
    notes: List<NoteEntry>,
    searchActive: Boolean,
    onSearchActiveChanged: (Boolean) -> Unit,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onSyncRequested: () -> Unit,
    onNoteClick: (NoteEntry) -> Unit,
    onCreateClick: () -> Unit,
    selectedNoteId: String?,
    singleColumn: Boolean = false,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (searchActive) {
                    // Same pill-on-surfaceContainerHighest treatment as `StatusPill`/
                    // `SlidingSegmentedControl` (see ui/components/Components.kt) rather than a
                    // bare TextField, so search reads as part of the same design system.
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        TextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChanged,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Search notes") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                    }
                    IconButton(onClick = { onSearchActiveChanged(false); onSearchQueryChanged("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close search")
                    }
                } else {
                    AppHeader(
                        leadingIcon = Icons.Filled.StickyNote2,
                        title = "Notes",
                        subtitle = "${notes.size} ${if (notes.size == 1) "note" else "notes"}",
                        modifier = Modifier.weight(1f),
                        iconContainerColor = AccentYellow.copy(alpha = 0.3f),
                        iconTint = AccentYellowOn,
                        trailing = {
                            // Same pill treatment as the editor's own toolbar groups (see
                            // NoteEditor) rather than two bare icons loose on the header, and
                            // sized down from the default 48dp touch target so the pair doesn't
                            // visually outweigh the title next to it. The corner radius is scaled
                            // down to match — the editor's pills are 16dp corners on a 48dp-tall
                            // button, and reusing that same 16dp here on a shorter 36dp button
                            // rounds off proportionally more, reading as a different (more
                            // "pill-shaped") shape instead of a smaller version of the same one.
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            ) {
                                IconButton(
                                    onClick = { onSearchActiveChanged(true) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = "Search notes",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = onSyncRequested,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Sync,
                                        contentDescription = "Sync notes with Mac",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    )
                }
            }

            val filtered = if (searchQuery.isBlank()) {
                notes
            } else {
                val q = searchQuery.trim()
                notes.filter { it.title.contains(q, ignoreCase = true) || it.body.contains(q, ignoreCase = true) }
            }
            val pinned = filtered.filter { it.isPinned }
            val others = filtered.filter { !it.isPinned }

            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (notes.isEmpty()) "No notes yet — tap + to create one." else "No matching notes",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (singleColumn) 1 else 2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (pinned.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionLabel("Pinned")
                        }
                        items(pinned, key = { it.id }) { note ->
                            NoteCard(note, selected = note.id == selectedNoteId, onClick = { onNoteClick(note) })
                        }
                    }
                    if (others.isNotEmpty()) {
                        if (pinned.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SectionLabel("Others")
                            }
                        }
                        items(others, key = { it.id }) { note ->
                            NoteCard(note, selected = note.id == selectedNoteId, onClick = { onNoteClick(note) })
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onCreateClick,
            containerColor = AccentYellow,
            contentColor = AccentYellowOn,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "New note")
        }
    }
}

/** A dragged-to-resize divider between the list-detail layout's two panes (see [NotesScreen]) —
 *  a plain [VerticalDivider] with a wider invisible drag target around it (dragging a 1px line
 *  directly is impractical), a small pill-shaped grip centered on it so it's discoverable as
 *  draggable on first glance rather than only once a mouse happens to hover it, and
 *  [PointerIcon.Companion.Hand] so a mouse/trackpad user gets that same cue. [onDragDp] receives
 *  the raw per-event horizontal drag delta already converted to dp; clamping that against the
 *  pane's min/max width is the caller's job. */
@Composable
private fun ResizableDivider(onDragDp: (Float) -> Unit) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(16.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDp(with(density) { dragAmount.x.toDp().value })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // The line sits centered in this 16dp hit-box, so its left half overlaps the list pane —
        // left uncolored, that half just showed the page's own (lighter) background instead of
        // the list's darker surfaceContainer, reading as the list panel stopping short of the
        // seam. Continuing that color to the line closes the gap.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(8.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainer)
        )
        VerticalDivider()
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    )
}

@Composable
private fun NoteCard(note: NoteEntry, selected: Boolean = false, onClick: () -> Unit) {
    // The raw body can contain an inline `![](data:...)` image token — a many-KB string with no
    // spaces, which crashes Compose's text layout if it's ever handed to a `Text` composable
    // directly (see NoteBlocks.kt). `previewText` always has tokens stripped; `coverImage` pulls
    // the first one out separately to show as an actual thumbnail instead.
    val previewText = remember(note.body) { noteBodyPreviewText(note.body) }
    val coverImageDataUrl = remember(note.body, note.imageBase64) { noteCoverImageDataUrl(note.body, note.imageBase64) }
    val coverBitmap = remember(coverImageDataUrl) { coverImageDataUrl?.let(::decodeNoteImage) }

    // `selected` only ever means anything in the expanded list-detail layout (see NotesScreen) —
    // marks which note the detail pane is currently showing, since there's no other visual cue
    // for that once the grid and the open note are both on screen at once. Yellow (Notes' own
    // accent — see the FAB/nav icon), not the app-wide green `primary`: this section reads as one
    // consistent color scheme rather than mixing in the global accent.
    val cardModifier = if (selected) {
        Modifier.fillMaxWidth().border(2.dp, AccentYellow, MaterialTheme.shapes.medium)
    } else {
        Modifier.fillMaxWidth()
    }
    LinkCard(onClick = onClick, modifier = cardModifier) {
        Column(Modifier.padding(12.dp)) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .clip(MaterialTheme.shapes.small)
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    note.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (note.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "Pinned",
                        tint = AccentYellow,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            if (previewText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    previewText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                formatTimestamp(note.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoteEditor(
    existing: NoteEntry?,
    onCancel: () -> Unit,
    onSave: (title: String, body: String) -> Unit,
    onDelete: (() -> Unit)?,
    onTogglePin: (() -> Unit)?,
    isListPaneCollapsed: Boolean = false,
    onToggleListPaneCollapsed: (() -> Unit)? = null
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    // The body is a sequence of blocks (see NoteBlocks.kt) rather than a single raw string, so an
    // inline image synced from the Mac renders as an actual picture instead of a wall of base64
    // text, and rich HTML (headings, bold/italic/strike, lists, checklists) from the Mac's
    // rich-text editor renders with real styling instead of literal tags.
    //
    // Whether this note's body is HTML-format is decided once, from what it looked like when
    // opened, and stays fixed for the whole editing session (see `serializeNoteBlocks`) — a new,
    // phone-created note (`existing == null`, body "") is never HTML, matching pre-existing
    // behavior exactly for notes that never touch the Mac's rich editor.
    val isHtml = remember { (existing?.body ?: "").trimStart().startsWith("<") }
    var blocks by remember { mutableStateOf(initialNoteBlocksFor(existing?.body ?: "", existing?.imageBase64)) }
    var confirmingDelete by remember { mutableStateOf(false) }

    // The block currently open for editing, if any — see the "Rich-text editing" section of
    // NoteBlocks.kt. At most one block is ever in this state; tapping a different block (or
    // saving/leaving) commits it back into `blocks` first via `commitEditing` below.
    var editing by remember { mutableStateOf<EditingBlock?>(null) }
    fun commitEditing() {
        val current = editing ?: return
        blocks = normalizeNoteBlocks(blocks.map { b -> if (b.id == current.blockId) commitEditingToBlock(current) else b })
        editing = null
    }
    fun beginEditing(block: NoteBlock) {
        commitEditing()
        editing = startEditing(block)
    }
    // The formatting toolbar is always visible (see the top bar below), not just while a block
    // happens to be focused — so a tap on Bold/Heading/etc. before anything is being edited needs
    // a block to actually apply to. Starting one on the trailing block (there's always at least
    // one — see `normalizeNoteBlocks`) is the same block plain typing would land in anyway.
    fun activeEditingOrStartLast(): EditingBlock {
        editing?.let { return it }
        val started = startEditing(blocks.last())
        editing = started
        return started
    }

    fun canSave() = title.isNotBlank() || blocks.any {
        (it is NoteBlock.Text && it.text.isNotBlank()) || it is NoteBlock.Image || it is NoteBlock.Rich
    }
    val goBack = {
        // Commits whatever's still open in the formatting toolbar into `blocks` first — otherwise
        // tapping Back/the system back button while a block is mid-edit would silently drop it,
        // since `blocks` (what actually gets serialized below) never received the update.
        commitEditing()
        if (canSave()) onSave(title.trim(), serializeNoteBlocks(blocks, isHtml).trim()) else onCancel()
    }

    // Without this, the system Back button falls straight through to the Activity (nothing else
    // intercepts it — see MainActivity/NotesScreen, neither uses Navigation's back stack) and
    // closes the whole app instead of returning to the notes grid. Sharing `goBack` with the
    // toolbar arrow below keeps this consistent with tapping it: save if there's anything to
    // save, otherwise just cancel.
    BackHandler(onBack = goBack)

    Column(modifier = Modifier.fillMaxSize()) {
        // Three distinct pills — Back, formatting, Pin/Delete — rather than one bar spanning the
        // whole width, matching how a real toolbar (e.g. Notes on the Mac) groups related actions
        // into separate capsules with visible gaps instead of one continuous surface. Always
        // visible here, not conditional on a block currently being focused, so it reads as a
        // permanent part of the editor rather than something that only appears once you've
        // already started typing. Tapping a formatting button before anything is focused starts
        // editing the trailing block first (see `activeEditingOrStartLast`).
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                IconButton(onClick = goBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            if (onToggleListPaneCollapsed != null) {
                // Dragging the divider itself can only give the formatting toolbar so much room
                // before its own pill runs out of space to scroll in — this is the actual "give
                // the editor more room" control: it hides the list pane outright rather than
                // squeezing it, so the toolbar keeps its full working width instead of just a
                // sliver of it.
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    IconButton(onClick = onToggleListPaneCollapsed) {
                        Icon(
                            if (isListPaneCollapsed) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = if (isListPaneCollapsed) "Show note list" else "Hide note list"
                        )
                    }
                }
            }
            NoteFormattingToolbar(
                editing = editing,
                onEnsureEditing = { activeEditingOrStartLast() },
                onChange = { editing = it },
                onDismissKeyboard = { commitEditing() },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            if (onTogglePin != null || onDelete != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    if (onTogglePin != null) {
                        IconButton(onClick = onTogglePin) {
                            Icon(
                                if (existing?.isPinned == true) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = if (existing?.isPinned == true) "Unpin" else "Pin"
                            )
                        }
                    }
                    if (onDelete != null) {
                        IconButton(onClick = { confirmingDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Title") },
                textStyle = MaterialTheme.typography.headlineSmall,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // "Start typing…" only makes sense when this is the only block — i.e. the note is
                // genuinely empty. An empty block that exists purely to hold the caret next to an
                // image (there's always at least one, by construction — see `normalizeNoteBlocks`)
                // would otherwise show the same placeholder floating below real content.
                val showPlaceholder = blocks.size == 1
                blocks.forEach { block ->
                    key(block.id) {
                        when (block) {
                            is NoteBlock.Image -> {
                                val bitmap = remember(block.dataUrl) { decodeNoteImage(block.dataUrl) }
                                if (bitmap != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp)
                                    ) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 260.dp)
                                                .clip(MaterialTheme.shapes.medium)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(8.dp)
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(Color.Black.copy(alpha = 0.5f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    blocks = normalizeNoteBlocks(blocks.filterNot { it.id == block.id })
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.Close,
                                                    contentDescription = "Remove image",
                                                    tint = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            is NoteBlock.Text, is NoteBlock.Rich -> {
                                val currentlyEditing = editing?.takeIf { it.blockId == block.id }
                                if (currentlyEditing != null) {
                                    EditingBlockField(
                                        editing = currentlyEditing,
                                        onChange = { editing = it },
                                        placeholder = if (showPlaceholder) "Start typing…" else null,
                                    )
                                } else if (block is NoteBlock.Text) {
                                    // Not focused right now — tapping opens the shared formatting-
                                    // toolbar editor (see EditingBlockField) instead of the plain
                                    // always-editable field this used to be, so a legacy plain-text
                                    // block can pick up formatting too, not just Rich ones.
                                    Text(
                                        text = block.text.ifEmpty { if (showPlaceholder) "Start typing…" else "" },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (block.text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { beginEditing(block) }
                                            .padding(vertical = 14.dp, horizontal = 4.dp)
                                    )
                                } else if (block is NoteBlock.Rich) {
                                    RichBlockView(
                                        block = block,
                                        onTapToEdit = { beginEditing(block) },
                                        onToggleTaskItem = { itemIndex ->
                                            blocks = blocks.map { b ->
                                                if (b.id == block.id && b is NoteBlock.Rich) toggleTaskItem(b, itemIndex) else b
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmingDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            }
        )
    }
}

/** The shared editor for whichever one block currently has [EditingBlock] state — a multi-line
 *  field showing [EditingBlock.value]'s live bold/italic/strike styling (Compose renders a
 *  [TextFieldValue]'s embedded spans while still allowing normal typing/selection over them,
 *  unlike a plain-`String`-backed field), paired with [NoteFormattingToolbar] in the top bar (see
 *  [NoteEditor]). A list kind ([EditKind.BULLET_LIST]/[EditKind.NUMBERED_LIST]/[EditKind.TASK_LIST])
 *  shows as one line per item, with a live "•"/"1."/"☐" prefix per line via [ListPrefixTransformation]
 *  so toggling one of those visibly does something immediately — the real bullet/number/checkbox
 *  glyphs (and, for a checklist, a genuinely tappable box) only reappear once editing ends and
 *  [RichBlockView] takes back over, but the field no longer looks indistinguishable from plain
 *  text in the meantime. */
@Composable
private fun EditingBlockField(
    editing: EditingBlock,
    onChange: (EditingBlock) -> Unit,
    placeholder: String?,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(editing.blockId) { focusRequester.requestFocus() }

    OutlinedTextField(
        value = editing.value,
        onValueChange = { newValue ->
            // The platform can hand back a plain reconstruction of any part of the text at any
            // point — same characters, but with custom AnnotatedString spans (bold/italic/strike)
            // stripped from some or all of it — not just right after a focus change but as a
            // routine part of how a soft keyboard resends an in-progress composing word on each
            // keystroke (autocorrect/predictive input). Trusting `newValue.annotatedString`
            // directly is what caused bold text to revert on the next keystroke, and turning a
            // mark off to bleed backwards into text already typed: fixed by never trusting the
            // platform's styling for text that already existed. Diff old vs. new text into a
            // common prefix/suffix (unchanged, so re-styled verbatim from the OLD annotated
            // string) and whatever's left in the middle (genuinely new/changed, so built as plain
            // text and — only this part — stamped with `pendingMarks` if a mark is currently
            // armed). A pure cursor/selection move with identical text is the prefix-is-everything
            // case, so this also replaces the old narrower "resync after focus change" guard.
            val oldText = editing.value.text
            val newText = newValue.text
            val maxCommon = minOf(oldText.length, newText.length)
            var prefixLen = 0
            while (prefixLen < maxCommon && oldText[prefixLen] == newText[prefixLen]) prefixLen++
            var suffixLen = 0
            val maxSuffix = maxCommon - prefixLen
            while (suffixLen < maxSuffix && oldText[oldText.length - 1 - suffixLen] == newText[newText.length - 1 - suffixLen]) {
                suffixLen++
            }
            val middleText = newText.substring(prefixLen, newText.length - suffixLen)
            val styled = buildAnnotatedString {
                append(editing.value.annotatedString.subSequence(0, prefixLen))
                val middleStart = length
                append(middleText)
                if (middleText.isNotEmpty() && editing.pendingMarks.isNotEmpty()) {
                    addStyle(
                        SpanStyle(
                            fontWeight = if (EditMark.BOLD in editing.pendingMarks) FontWeight.Bold else null,
                            fontStyle = if (EditMark.ITALIC in editing.pendingMarks) FontStyle.Italic else null,
                            textDecoration = if (EditMark.STRIKE in editing.pendingMarks) TextDecoration.LineThrough else null
                        ),
                        middleStart,
                        length
                    )
                }
                append(editing.value.annotatedString.subSequence(oldText.length - suffixLen, oldText.length))
            }
            val effectiveValue = TextFieldValue(styled, newValue.selection, newValue.composition)
            val newLineCount = effectiveValue.text.count { it == '\n' } + 1
            val newChecked = if (editing.kind == EditKind.TASK_LIST) {
                when {
                    newLineCount > editing.taskChecked.size ->
                        editing.taskChecked + List(newLineCount - editing.taskChecked.size) { false }
                    newLineCount < editing.taskChecked.size -> editing.taskChecked.take(newLineCount)
                    else -> editing.taskChecked
                }
            } else {
                editing.taskChecked
            }
            onChange(editing.copy(value = effectiveValue, taskChecked = newChecked))
        },
        placeholder = if (placeholder != null) {
            { Text(placeholder) }
        } else null,
        textStyle = if (editing.kind == EditKind.HEADING) {
            MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        } else {
            MaterialTheme.typography.bodyLarge
        },
        visualTransformation = when (editing.kind) {
            EditKind.BULLET_LIST -> ListPrefixTransformation { "•  " }
            EditKind.NUMBERED_LIST -> ListPrefixTransformation { line -> "${line + 1}.  " }
            EditKind.TASK_LIST -> ListPrefixTransformation { line ->
                if (editing.taskChecked.getOrElse(line) { false }) "☑  " else "☐  "
            }
            EditKind.PARAGRAPH, EditKind.HEADING -> VisualTransformation.None
        },
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
    )
}

/** Renders a "•  "/"1.  "/"☐  " prefix on every line without it actually being part of the
 *  editable text — [EditingBlock.value] stays plain per-item text (what [commitEditingToBlock]
 *  reads), while the field visibly reads as a list/checklist while it's being edited instead of
 *  looking identical to plain paragraph text. [prefixFor] is re-evaluated fresh each recomposition
 *  (e.g. a checklist's checked glyphs), so this is a plain function, not `remember`ed. */
private class ListPrefixTransformation(private val prefixFor: (lineIndex: Int) -> String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val lines = text.text.split("\n")
        val prefixes = lines.indices.map { prefixFor(it) }
        val originalLineStarts = IntArray(lines.size)
        val transformedLineStarts = IntArray(lines.size)
        var originalPos = 0
        var transformedPos = 0
        for (i in lines.indices) {
            originalLineStarts[i] = originalPos
            transformedLineStarts[i] = transformedPos
            originalPos += lines[i].length + 1
            transformedPos += prefixes[i].length + lines[i].length + 1
        }
        val transformed = buildAnnotatedString {
            lines.forEachIndexed { i, line ->
                if (i > 0) append("\n")
                append(prefixes[i])
                append(text.subSequence(originalLineStarts[i], originalLineStarts[i] + line.length))
            }
        }
        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val i = (lines.indices).lastOrNull { originalLineStarts[it] <= offset } ?: 0
                return transformedLineStarts[i] + prefixes[i].length + (offset - originalLineStarts[i])
            }

            override fun transformedToOriginal(offset: Int): Int {
                val i = (lines.indices).lastOrNull { transformedLineStarts[it] <= offset } ?: 0
                val withinLine = (offset - transformedLineStarts[i] - prefixes[i].length).coerceAtLeast(0)
                return (originalLineStarts[i] + withinLine).coerceAtMost(originalLineStarts[i] + lines[i].length)
            }
        }
        return TransformedText(transformed, offsetMapping)
    }
}

/** The formatting toolbar — always visible in [NoteEditor], as its own filled/rounded surface
 *  (not icons sitting directly on the page background), rather than something that only appears
 *  once a block happens to be focused. [editing] is nullable for exactly that reason: nothing may
 *  be focused yet when a button is tapped, in which case [onEnsureEditing] starts an edit session
 *  (on the trailing block — see `activeEditingOrStartLast` in [NoteEditor]) before the action
 *  applies, and every button's active/inactive tint just falls back to "inactive" while nothing is
 *  focused. Bold/Italic/Strikethrough act on the current text selection when there is one; with
 *  nothing selected (just a cursor, or nothing focused at all yet) they arm instead, so whatever's
 *  typed next picks up the mark — see [applyMarkToggle]. Heading/Checklist/Bulleted/Numbered switch
 *  the whole block's kind, toggling back to a plain paragraph if it's already that kind. The
 *  trailing checkmark dismisses the keyboard/ends the current edit; it's harmless to tap with
 *  nothing focused. */
@Composable
private fun NoteFormattingToolbar(
    editing: EditingBlock?,
    onEnsureEditing: () -> EditingBlock,
    onChange: (EditingBlock) -> Unit,
    onDismissKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun apply(transform: (EditingBlock) -> EditingBlock) {
        onChange(transform(editing ?: onEnsureEditing()))
    }
    fun toggleMark(mark: EditMark) = apply { applyMarkToggle(it, mark) }

    // No background/clip of its own — the caller's Row (see NoteEditor) is the one distinct
    // surface the whole top bar shares with Back/Pin/Delete, rather than this being a separate
    // pill floating between icons loose on the page background.
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            FormattingToolbarButton(
                Icons.Filled.FormatBold,
                "Bold",
                active = editing?.let { isMarkActive(it, EditMark.BOLD) } ?: false
            ) { toggleMark(EditMark.BOLD) }
            FormattingToolbarButton(
                Icons.Filled.FormatItalic,
                "Italic",
                active = editing?.let { isMarkActive(it, EditMark.ITALIC) } ?: false
            ) { toggleMark(EditMark.ITALIC) }
            FormattingToolbarButton(
                Icons.Filled.FormatStrikethrough,
                "Strikethrough",
                active = editing?.let { isMarkActive(it, EditMark.STRIKE) } ?: false
            ) { toggleMark(EditMark.STRIKE) }
            FormattingToolbarButton(Icons.Filled.Title, "Heading", active = editing?.kind == EditKind.HEADING) {
                apply { toggleEditKind(it, EditKind.HEADING) }
            }
            FormattingToolbarButton(Icons.Filled.CheckBox, "Checklist", active = editing?.kind == EditKind.TASK_LIST) {
                apply { toggleEditKind(it, EditKind.TASK_LIST) }
            }
            FormattingToolbarButton(
                Icons.Filled.FormatListBulleted,
                "Bulleted list",
                active = editing?.kind == EditKind.BULLET_LIST
            ) { apply { toggleEditKind(it, EditKind.BULLET_LIST) } }
            FormattingToolbarButton(
                Icons.Filled.FormatListNumbered,
                "Numbered list",
                active = editing?.kind == EditKind.NUMBERED_LIST
            ) { apply { toggleEditKind(it, EditKind.NUMBERED_LIST) } }
        }
        IconButton(onClick = onDismissKeyboard) {
            Icon(Icons.Filled.Check, contentDescription = "Dismiss keyboard", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FormattingToolbarButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) AccentYellow else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Renders one HTML-parsed rich block (heading/paragraph/bullet-or-numbered list/task list) with
 *  real Compose styling — actual bold/italic/strikethrough spans, a larger heading, real bullet/
 *  number markers, and interactive checkboxes for a task list.
 *
 *  Tapping a block's text calls [onTapToEdit], which opens the shared formatting-toolbar editor
 *  (see [EditingBlockField]/[NoteFormattingToolbar] and `beginEditing` in [NoteEditor]). A task
 *  item's checkbox is the one exception: it's wired to [onToggleTaskItem] instead, so checking it
 *  off updates just that item's `data-checked` in place rather than opening the editor. */
@Composable
private fun RichBlockView(
    block: NoteBlock.Rich,
    onTapToEdit: () -> Unit,
    onToggleTaskItem: (itemIndex: Int) -> Unit
) {
    when (val display = block.display) {
        is RichDisplay.Heading -> {
            Text(
                text = runsToAnnotatedString(display.runs),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onTapToEdit)
                    .padding(vertical = 10.dp)
            )
        }
        is RichDisplay.Paragraph -> {
            Text(
                text = runsToAnnotatedString(display.runs),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onTapToEdit)
                    .padding(vertical = 10.dp, horizontal = 4.dp)
            )
        }
        is RichDisplay.BulletList -> {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                display.items.forEach { runs ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onTapToEdit)
                            .padding(vertical = 4.dp, horizontal = 4.dp)
                    ) {
                        Text("•", modifier = Modifier.width(20.dp), style = MaterialTheme.typography.bodyLarge)
                        Text(runsToAnnotatedString(runs), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        is RichDisplay.NumberedList -> {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                display.items.forEachIndexed { index, runs ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onTapToEdit)
                            .padding(vertical = 4.dp, horizontal = 4.dp)
                    ) {
                        Text("${index + 1}.", modifier = Modifier.width(24.dp), style = MaterialTheme.typography.bodyLarge)
                        Text(runsToAnnotatedString(runs), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        is RichDisplay.TaskList -> {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                display.items.forEachIndexed { index, item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Checkbox(checked = item.checked, onCheckedChange = { onToggleTaskItem(index) })
                        Text(
                            text = runsToAnnotatedString(item.runs),
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                            color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(onClick = onTapToEdit)
                                .padding(vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

// runsToAnnotatedString now lives in NoteBlocks.kt (same package) — shared with the editing code
// there, which needed it too.

private fun formatTimestamp(epochMillis: Double): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis.toLong()))
