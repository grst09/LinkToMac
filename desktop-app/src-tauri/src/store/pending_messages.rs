//! Outbound texts composed on the Mac while `SyncSettingsPayload.calls_and_messages_enabled` is
//! off. `send_sms` still shows the usual local-echo copy (see `commands/messages.rs`) so the
//! Mac's own thread view looks like it was sent, but the actual `sms.send` to the phone is
//! deferred and queued here until sync comes back on.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use super::local_notes::generate_local_id;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingMessage {
    pub id: String,
    pub address: String,
    pub body: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct PendingMessagesData {
    pending: Vec<PendingMessage>,
}

pub struct PendingMessagesStore {
    file_path: PathBuf,
    data: PendingMessagesData,
}

impl PendingMessagesStore {
    pub fn load_or_create(app_data_dir: &Path) -> std::io::Result<Self> {
        std::fs::create_dir_all(app_data_dir)?;
        let file_path = app_data_dir.join("pending_messages.json");
        let data = Self::try_load(&file_path).unwrap_or_default();
        Ok(Self { file_path, data })
    }

    fn try_load(path: &Path) -> Option<PendingMessagesData> {
        let data = std::fs::read_to_string(path).ok()?;
        serde_json::from_str(&data).ok()
    }

    fn save(&self) -> std::io::Result<()> {
        let data = serde_json::to_string(&self.data)?;
        std::fs::write(&self.file_path, data)
    }

    pub fn pending(&self) -> Vec<PendingMessage> {
        self.data.pending.clone()
    }

    pub fn add(&mut self, address: String, body: String) -> PendingMessage {
        let message = PendingMessage {
            id: generate_local_id(),
            address,
            body,
        };
        self.data.pending.push(message.clone());
        let _ = self.save();
        message
    }

    pub fn take_all(&mut self) -> Vec<PendingMessage> {
        let taken = std::mem::take(&mut self.data.pending);
        let _ = self.save();
        taken
    }
}
