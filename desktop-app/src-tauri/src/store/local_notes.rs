//! Notes created/edited on the Mac while `SyncSettingsPayload.notes_enabled` is off — kept
//! entirely local (never sent to the phone) until sync is re-enabled, at which point
//! `dispatch::sync_settings::reconcile_notes` pushes each one as a brand-new `notes.create`
//! (never as an update against a possibly-since-changed phone note — see that module's doc
//! comment for why "create fresh" beats "overwrite"). Same `load_or_create`/`save`-on-every-
//! mutation shape as `SettingsStore`, just keyed by a flat pending list instead of a single value.

use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingNote {
    pub id: String,
    pub title: String,
    pub body: String,
    pub created_at: f64,
    pub updated_at: f64,
    #[serde(default)]
    pub is_pinned: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub image_base64: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct LocalNotesData {
    pending: Vec<PendingNote>,
}

pub struct LocalNotesStore {
    file_path: PathBuf,
    data: LocalNotesData,
}

impl LocalNotesStore {
    pub fn load_or_create(app_data_dir: &Path) -> std::io::Result<Self> {
        std::fs::create_dir_all(app_data_dir)?;
        let file_path = app_data_dir.join("local_notes.json");
        let data = Self::try_load(&file_path).unwrap_or_default();
        Ok(Self { file_path, data })
    }

    fn try_load(path: &Path) -> Option<LocalNotesData> {
        let data = std::fs::read_to_string(path).ok()?;
        serde_json::from_str(&data).ok()
    }

    fn save(&self) -> std::io::Result<()> {
        let data = serde_json::to_string(&self.data)?;
        std::fs::write(&self.file_path, data)
    }

    pub fn pending(&self) -> Vec<PendingNote> {
        self.data.pending.clone()
    }

    pub fn add(&mut self, title: String, body: String, image_base64: Option<String>) -> PendingNote {
        let now = now_millis();
        let note = PendingNote {
            id: generate_local_id(),
            title,
            body,
            created_at: now,
            updated_at: now,
            is_pinned: false,
            image_base64,
        };
        self.data.pending.push(note.clone());
        let _ = self.save();
        note
    }

    pub fn set_pinned(&mut self, id: &str, is_pinned: bool) -> bool {
        let Some(note) = self.data.pending.iter_mut().find(|n| n.id == id) else {
            return false;
        };
        note.is_pinned = is_pinned;
        let _ = self.save();
        true
    }

    /// `id` must be a locally-generated id (see `generate_local_id`) — the caller is
    /// responsible for routing phone-origin ids elsewhere.
    pub fn update(&mut self, id: &str, title: String, body: String, image_base64: Option<String>) -> bool {
        let Some(note) = self.data.pending.iter_mut().find(|n| n.id == id) else {
            return false;
        };
        note.title = title;
        note.body = body;
        note.image_base64 = image_base64;
        note.updated_at = now_millis();
        let _ = self.save();
        true
    }

    pub fn remove(&mut self, id: &str) -> bool {
        let before = self.data.pending.len();
        self.data.pending.retain(|n| n.id != id);
        let changed = self.data.pending.len() != before;
        if changed {
            let _ = self.save();
        }
        changed
    }

    /// Drains the whole pending queue — used by reconciliation, which pushes every entry to the
    /// phone and then wants the local queue empty regardless of whether each push succeeded
    /// (a `send_to_active` failure here means "not connected", not "rejected"; there's no
    /// per-item retry, matching how every other outbound command in this app already behaves).
    pub fn take_all(&mut self) -> Vec<PendingNote> {
        let taken = std::mem::take(&mut self.data.pending);
        let _ = self.save();
        taken
    }
}

pub fn generate_local_id() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    format!("local-{nanos:x}")
}

fn now_millis() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as f64)
        .unwrap_or(0.0)
}
