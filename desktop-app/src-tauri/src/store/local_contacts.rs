//! Contacts created on the Mac while `SyncSettingsPayload.contacts_enabled` is off — same
//! local-queue-then-reconcile shape as `local_notes.rs`. Editing/deleting an existing
//! phone-origin contact while sync is off is rejected outright by the `update_contact`/
//! `delete_contact` commands (no merge story for mutating something we don't have live access
//! to) — only brand-new contacts get queued here.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingContact {
    pub id: String,
    pub name: String,
    pub phone_number: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub email: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub organization: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct LocalContactsData {
    pending: Vec<PendingContact>,
}

pub struct LocalContactsStore {
    file_path: PathBuf,
    data: LocalContactsData,
}

impl LocalContactsStore {
    pub fn load_or_create(app_data_dir: &Path) -> std::io::Result<Self> {
        std::fs::create_dir_all(app_data_dir)?;
        let file_path = app_data_dir.join("local_contacts.json");
        let data = Self::try_load(&file_path).unwrap_or_default();
        Ok(Self { file_path, data })
    }

    fn try_load(path: &Path) -> Option<LocalContactsData> {
        let data = std::fs::read_to_string(path).ok()?;
        serde_json::from_str(&data).ok()
    }

    fn save(&self) -> std::io::Result<()> {
        let data = serde_json::to_string(&self.data)?;
        std::fs::write(&self.file_path, data)
    }

    pub fn pending(&self) -> Vec<PendingContact> {
        self.data.pending.clone()
    }

    pub fn add(
        &mut self,
        name: String,
        phone_number: String,
        email: Option<String>,
        organization: Option<String>,
    ) -> PendingContact {
        let contact = PendingContact {
            id: super::local_notes::generate_local_id(),
            name,
            phone_number,
            email,
            organization,
        };
        self.data.pending.push(contact.clone());
        let _ = self.save();
        contact
    }

    pub fn update(
        &mut self,
        id: &str,
        name: String,
        phone_number: String,
        email: Option<String>,
        organization: Option<String>,
    ) -> bool {
        let Some(contact) = self.data.pending.iter_mut().find(|c| c.id == id) else {
            return false;
        };
        contact.name = name;
        contact.phone_number = phone_number;
        contact.email = email;
        contact.organization = organization;
        let _ = self.save();
        true
    }

    pub fn remove(&mut self, id: &str) -> bool {
        let before = self.data.pending.len();
        self.data.pending.retain(|c| c.id != id);
        let changed = self.data.pending.len() != before;
        if changed {
            let _ = self.save();
        }
        changed
    }

    pub fn take_all(&mut self) -> Vec<PendingContact> {
        let taken = std::mem::take(&mut self.data.pending);
        let _ = self.save();
        taken
    }
}
