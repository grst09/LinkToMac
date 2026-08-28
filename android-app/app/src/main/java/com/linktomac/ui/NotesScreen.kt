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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linktomac.net.NoteEntry
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
fun NotesScreen(noteStore: NoteStore, onChanged: () -> Unit, onSyncRequested: () -> Unit) {
    var notes by remember { mutableStateOf(noteStore.readAll()) }
    var editing by remember { mutableStateOf<NoteEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    fun refresh() {
        notes = noteStore.readAll()
    }

    // Opening/closing a note used to be an instant hard cut between the grid and the editor —
    // this crossfades with a slight scale, like the note "lifting off" the grid on the way in
    // and settling back into it on the way out, instead of just snapping between two screens.
    AnimatedContent(
        targetState = creating || editing != null,
        transitionSpec = {
            val opening = targetState
            (fadeIn(tween(220)) + scaleIn(initialScale = if (opening) 0.94f else 1.05f, animationSpec = tween(220)))
                .togetherWith(fadeOut(tween(160)) + scaleOut(targetScale = if (opening) 1.05f else 0.94f, animationSpec = tween(160)))
                .using(SizeTransform(clip = false))
        },
        label = "note-detail-transition"
    ) { showingEditor ->
        if (showingEditor) {
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
                }
            )
        } else {
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
                                onValueChange = { searchQuery = it },
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
                        IconButton(onClick = { searchActive = false; searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                    } else {
                        AppHeader(
                            leadingIcon = Icons.AutoMirrored.Filled.Notes,
                            title = "Notes",
                            subtitle = "${notes.size} ${if (notes.size == 1) "note" else "notes"}",
                            modifier = Modifier.weight(1f),
                            trailing = {
                                Row {
                                    IconButton(onClick = { searchActive = true }) {
                                        Icon(Icons.Filled.Search, contentDescription = "Search notes")
                                    }
                                    IconButton(onClick = onSyncRequested) {
                                        Icon(Icons.Filled.Sync, contentDescription = "Sync notes with Mac")
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
                        columns = GridCells.Fixed(2),
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
                                NoteCard(note, onClick = { editing = note })
                            }
                        }
                        if (others.isNotEmpty()) {
                            if (pinned.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    SectionLabel("Others")
                                }
                            }
                            items(others, key = { it.id }) { note ->
                                NoteCard(note, onClick = { editing = note })
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = { creating = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New note")
            }
            }
        }
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
private fun NoteCard(note: NoteEntry, onClick: () -> Unit) {
    // The raw body can contain an inline `![](data:...)` image token — a many-KB string with no
    // spaces, which crashes Compose's text layout if it's ever handed to a `Text` composable
    // directly (see NoteBlocks.kt). `previewText` always has tokens stripped; `coverImage` pulls
    // the first one out separately to show as an actual thumbnail instead.
    val previewText = remember(note.body) { noteBodyPreviewText(note.body) }
    val coverImageDataUrl = remember(note.body, note.imageBase64) { noteCoverImageDataUrl(note.body, note.imageBase64) }
    val coverBitmap = remember(coverImageDataUrl) { coverImageDataUrl?.let(::decodeNoteImage) }

    LinkCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
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
                        tint = MaterialTheme.colorScheme.primary,
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
    onTogglePin: (() -> Unit)?
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    // The body is a sequence of text/image blocks (see NoteBlocks.kt) rather than a single raw
    // string, so an inline image synced from the Mac renders as an actual picture instead of a
    // wall of base64 text — and so it doesn't crash Compose's text layout getting there.
    var blocks by remember { mutableStateOf(initialNoteBlocksFor(existing?.body ?: "", existing?.imageBase64)) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val canSave = title.isNotBlank() || blocks.any {
        (it is NoteBlock.Text && it.text.isNotBlank()) || it is NoteBlock.Image
    }
    val goBack = { if (canSave) onSave(title.trim(), serializeNoteBlocks(blocks).trim()) else onCancel() }

    // Without this, the system Back button falls straight through to the Activity (nothing else
    // intercepts it — see MainActivity/NotesScreen, neither uses Navigation's back stack) and
    // closes the whole app instead of returning to the notes grid. Sharing `goBack` with the
    // toolbar arrow below keeps this consistent with tapping it: save if there's anything to
    // save, otherwise just cancel.
    BackHandler(onBack = goBack)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = goBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.weight(1f))
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
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
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
                            is NoteBlock.Text -> {
                                OutlinedTextField(
                                    value = block.text,
                                    onValueChange = { newText ->
                                        blocks = blocks.map { b ->
                                            if (b.id == block.id && b is NoteBlock.Text) b.copy(text = newText) else b
                                        }
                                    },
                                    placeholder = if (showPlaceholder) {
                                        { Text("Start typing…") }
                                    } else null,
                                    textStyle = MaterialTheme.typography.bodyLarge,
                                    colors = TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
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

private fun formatTimestamp(epochMillis: Double): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis.toLong()))
