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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linktomac.net.NoteEntry
import com.linktomac.storage.NoteStore
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/**
 * Notes list + create/edit/delete, all local to the phone (see docs/PROTOCOL.md's Phase 8 notes
 * on why there's no system provider to mirror here). Every mutation calls [onChanged] so the
 * caller can push a fresh `notes.sync` to the Mac — this screen doesn't touch [MacConnection]
 * itself, same separation PairingScreen keeps from SyncForegroundService.
 *
 * Visually modeled on Samsung Notes: a colorful two-column card grid with a pinned section,
 * search, and a FAB for new notes, rather than a plain list.
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (searchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Search notes") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        )
                        IconButton(onClick = { searchActive = false; searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                    } else {
                        Text("Notes", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search notes")
                        }
                        IconButton(onClick = onSyncRequested) {
                            Icon(Icons.Filled.Sync, contentDescription = "Sync notes with Mac")
                        }
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

/** A small fixed palette, indexed deterministically by note id — mimics Samsung Notes' colorful
 *  card grid without needing a synced "color" field (would mean a protocol change just for a
 *  cosmetic per-note property; deriving it from the id keeps the color stable across sessions
 *  and platforms without extra state). */
private val cardPalette = listOf(
    Color(0xFFFFF3B0), // pale yellow
    Color(0xFFC8F4DE), // pale mint
    Color(0xFFD6E4FF), // pale blue
    Color(0xFFFFE0EC), // pale pink
    Color(0xFFE6DBFF), // pale lavender
    Color(0xFFFFE3C8) // pale peach
)

private fun cardColorFor(id: String): Color = cardPalette[abs(id.hashCode()) % cardPalette.size]

@Composable
private fun NoteCard(note: NoteEntry, onClick: () -> Unit) {
    val background = cardColorFor(note.id)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = background),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    note.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF1A1A1A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (note.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "Pinned",
                        tint = Color(0xFF1A1A1A).copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            if (note.body.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    note.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF1A1A1A).copy(alpha = 0.75f),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                formatTimestamp(note.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF1A1A1A).copy(alpha = 0.5f)
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
    var body by remember { mutableStateOf(existing?.body ?: "") }
    var confirmingDelete by remember { mutableStateOf(false) }
    val canSave = title.isNotBlank() || body.isNotBlank()
    val goBack = { if (canSave) onSave(title.trim(), body.trim()) else onCancel() }

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
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text("Start typing…") },
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
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
