//! A note change aimed at a phone-origin note (`NoteEntry.id` not prefixed `local-`) that
//! couldn't reach the phone when the user made it — no active connection, most commonly.
//! `commands::notes` applies it to the in-memory `AppState.notes` snapshot immediately (so the
//! UI reflects it without waiting for the phone) and queues it here; `dispatch::sync_settings`
//! replays the queue against the phone every time it reconnects and drops each entry once it
//! actually lands. `dispatch::notes::sync` also re-applies whatever's still queued on top of any
//! `notes.sync` snapshot that arrives in the meantime, so a stale phone snapshot racing the
//! reconnect can't visually undo it.
//!
//! `Change` carries an offline pin toggle and an offline content edit (title/body/image)
//! *together*, since both can happen to the same note while disconnected and neither should
//! silently drop the other. Both are replayed blind on reconnect — a pin toggle only has two
//! states, and a content edit is the Mac's own last-written value winning outright, which is the
//! right call for a personal single-user notes app with no live merge story (same assumption the
//! original pin/delete-only version of this queue already made).

use std::collections::HashSet;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingNoteContent {
    pub title: String,
    pub body: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub image_base64: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum PendingNoteMutation {
    Delete {
        id: String,
    },
    Change {
        id: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        set_pinned: Option<bool>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        content: Option<PendingNoteContent>,
    },
}

impl PendingNoteMutation {
    pub fn note_id(&self) -> &str {
        match self {
            PendingNoteMutation::Delete { id } => id,
            PendingNoteMutation::Change { id, .. } => id,
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

    fn is_deleting(&self, id: &str) -> bool {
        self.data
            .iter()
            .any(|m| matches!(m, PendingNoteMutation::Delete { id: existing } if existing == id))
    }

    /// Finds (or creates) the single `Change` entry for `id`, so a pin toggle and a content edit
    /// queued at different moments merge into one entry instead of one clobbering the other.
    fn change_entry(&mut self, id: &str) -> &mut PendingNoteMutation {
        if let Some(idx) = self
            .data
            .iter()
            .position(|m| matches!(m, PendingNoteMutation::Change { id: existing, .. } if existing == id))
        {
            return &mut self.data[idx];
        }
        self.data.push(PendingNoteMutation::Change {
            id: id.to_string(),
            set_pinned: None,
            content: None,
        });
        self.data.last_mut().expect("just pushed")
    }

    /// A note already queued for deletion has nothing left to pin — drop the toggle rather than
    /// queue a pin for a note that's about to disappear.
    pub fn queue_set_pinned(&mut self, id: String, is_pinned: bool) {
        if self.is_deleting(&id) {
            return;
        }
        if let PendingNoteMutation::Change { set_pinned, .. } = self.change_entry(&id) {
            *set_pinned = Some(is_pinned);
        }
        self.save();
    }

    /// Same reasoning as `queue_set_pinned` — nothing to update on a note that's about to be
    /// deleted.
    pub fn queue_update(&mut self, id: String, title: String, body: String, image_base64: Option<String>) {
        if self.is_deleting(&id) {
            return;
        }
        if let PendingNoteMutation::Change { content, .. } = self.change_entry(&id) {
            *content = Some(PendingNoteContent { title, body, image_base64 });
        }
        self.save();
    }

    /// A delete supersedes anything else already queued for this note (e.g. a pin toggle or edit
    /// made moments before).
    pub fn queue_delete(&mut self, id: String) {
        self.data.retain(|m| m.note_id() != id);
        self.data.push(PendingNoteMutation::Delete { id });
        self.save();
    }

    /// Drops every queued mutation whose note id appears in `succeeded` — called after
    /// reconciliation has replayed each one against the phone. Matching by id rather than by
    /// value is safe: `queue_set_pinned`/`queue_update`/`queue_delete` never let more than one
    /// mutation exist per note id at a time. A `Change` with multiple parts (pin + content) is
    /// only ever passed as `succeeded` once *every* part present replayed successfully — a
    /// partial failure leaves the whole entry queued to retry both next time, which is harmless
    /// to resend (a pin toggle and a content overwrite are both idempotent).
    pub fn remove(&mut self, succeeded: &[PendingNoteMutation]) {
        if succeeded.is_empty() {
            return;
        }
        let ids: HashSet<&str> = succeeded.iter().map(PendingNoteMutation::note_id).collect();
        self.data.retain(|m| !ids.contains(m.note_id()));
        self.save();
    }
}
