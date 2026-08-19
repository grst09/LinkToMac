package com.linktomac.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linktomac.net.NoteEntry
import com.linktomac.storage.NoteStore
import java.text.DateFormat
import java.util.Date

/**
 * Notes list + create/edit/delete, all local to the phone (see docs/PROTOCOL.md's Phase 8 notes
 * on why there's no system provider to mirror here). Every mutation calls [onChanged] so the
 * caller can push a fresh `notes.sync` to the Mac — this screen doesn't touch [MacConnection]
 * itself, same separation PairingScreen keeps from SyncForegroundService.
 */
@Composable
fun NotesScreen(noteStore: NoteStore, onChanged: () -> Unit) {
    var notes by remember { mutableStateOf(noteStore.readAll()) }
    var editing by remember { mutableStateOf<NoteEntry?>(null) }
    var creating by remember { mutableStateOf(false) }

    fun refresh() {
        notes = noteStore.readAll()
    }

    if (creating || editing != null) {
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
            }
        )
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Notes", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Button(onClick = { creating = true }) {
                    Text("New Note")
                }
            }
            Spacer(Modifier.height(16.dp))
            if (notes.isEmpty()) {
                Text(
                    "No notes yet — tap New Note to create one. Notes you add here sync to your Mac automatically.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(notes, key = { it.id }) { note ->
                        NoteRow(note, onClick = { editing = note })
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(note: NoteEntry, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                note.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (note.body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    note.body,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(formatTimestamp(note.updatedAt), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun NoteEditor(
    existing: NoteEntry?,
    onCancel: () -> Unit,
    onSave: (title: String, body: String) -> Unit,
    onDelete: (() -> Unit)?
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var body by remember { mutableStateOf(existing?.body ?: "") }
    var confirmingDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(if (existing != null) "Edit Note" else "New Note", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        Spacer(Modifier.height(12.dp))
        Row {
            Button(onClick = { onSave(title.trim(), body.trim()) }, enabled = title.isNotBlank() || body.isNotBlank()) {
                Text(if (existing != null) "Save" else "Create")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
            if (onDelete != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { confirmingDelete = true }) {
                    Text("Delete")
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
