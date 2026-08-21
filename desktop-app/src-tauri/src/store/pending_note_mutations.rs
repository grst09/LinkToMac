//! A pin or delete aimed at a phone-origin note (`NoteEntry.id` not prefixed `local-`) that
//! couldn't reach the phone when the user made it — no active connection, most commonly.
//! `commands::notes` applies it to the in-memory `AppState.notes` snapshot immediately (so the
//! UI reflects it without waiting for the phone) and queues it here; `dispatch::sync_settings`
//! replays the queue against the phone every time it reconnects and drops each entry once it
//! actually lands. `dispatch::notes::sync` also re-applies whatever's still queued on top of any
//! `notes.sync` snapshot that arrives in the meantime, so a stale phone snapshot racing the
//! reconnect can't visually undo it.
//!
//! Deliberately scoped to pin/delete, not edits — both are safe to replay blind (a delete stays
//! a delete, a pin toggle only has two states), which isn't true of blindly overwriting
//! `title`/`body` against whatever the phone's own copy became in the meantime.

use std::collections::HashSet;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum PendingNoteMutation {
    SetPinned { id: String, is_pinned: bool },
    Delete { id: String },
}

impl PendingNoteMutation {
    pub fn note_id(&self) -> &str {
        match self {
            PendingNoteMutation::SetPinned { id, .. } => id,
            PendingNoteMutation::Delete { id } => id,
        }
    }
}

pub struct PendingNoteMutationsStore {
    file_path: PathBuf,
    data: Vec<PendingNoteMutation>,
}

impl PendingNoteMutationsStore {
    pub fn load_or_create(app_data_dir: &Path) -> std::io::Result<Self> {
        std::fs::create_dir_all(app_data_dir)?;
        let file_path = app_data_dir.join("pending_note_mutations.json");
        let data = Self::try_load(&file_path).unwrap_or_default();
        Ok(Self { file_path, data })
    }

    fn try_load(path: &Path) -> Option<Vec<PendingNoteMutation>> {
        let data = std::fs::read_to_string(path).ok()?;
        serde_json::from_str(&data).ok()
    }

    fn save(&self) {
        if let Ok(json) = serde_json::to_string(&self.data) {
            let _ = std::fs::write(&self.file_path, json);
        }
    }

    pub fn all(&self) -> Vec<PendingNoteMutation> {
        self.data.clone()
    }

    /// A note already queued for deletion has nothing left to pin — drop the toggle rather than
    /// queue a pin for a note that's about to disappear.
    pub fn queue_set_pinned(&mut self, id: String, is_pinned: bool) {
        let already_deleting = self
            .data
            .iter()
            .any(|m| matches!(m, PendingNoteMutation::Delete { id: existing } if existing == &id));
        if already_deleting {
            return;
        }
        self.data.retain(|m| m.note_id() != id);
        self.data.push(PendingNoteMutation::SetPinned { id, is_pinned });
        self.save();
    }

    /// A delete supersedes anything else already queued for this note (e.g. a pin toggle made
    /// moments before).
    pub fn queue_delete(&mut self, id: String) {
        self.data.retain(|m| m.note_id() != id);
        self.data.push(PendingNoteMutation::Delete { id });
        self.save();
    }

    /// Drops every queued mutation whose note id appears in `succeeded` — called after
    /// reconciliation has replayed each one against the phone. Matching by id rather than by
    /// value is safe: `queue_set_pinned`/`queue_delete` never let more than one mutation exist
    /// per note id at a time.
    pub fn remove(&mut self, succeeded: &[PendingNoteMutation]) {
        if succeeded.is_empty() {
            return;
        }
        let ids: HashSet<&str> = succeeded.iter().map(PendingNoteMutation::note_id).collect();
        self.data.retain(|m| !ids.contains(m.note_id()));
        self.save();
    }
}
