//! State for the WhatsApp tab. Unlike SMS (pushed over the phone's own encrypted protocol,
//! see `net/server.rs`), WhatsApp messages come from a bundled Node child process ("the
//! bridge", `desktop-app/whatsapp-bridge/`) that speaks the same WhatsApp multi-device
//! "linked device" protocol web.whatsapp.com and WhatsApp Desktop use, via the Baileys
//! library — see `bridge.rs` for the process management and stdio wire format.

pub mod bridge;

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub enum LinkStatus {
    #[default]
    Unlinked,
    Qr,
    Connecting,
    Open,
    LoggedOut,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WaChat {
    pub id: String,
    pub name: Option<String>,
    pub is_group: bool,
    pub unread_count: i64,
    pub conversation_timestamp: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WaReaction {
    pub text: String,
    pub target_id: Option<String>,
}

/// Mirrors `whatsapp-bridge/index.js`'s `serializeMessage` output exactly — one flat shape
/// with per-type fields left `None` when not applicable, since the wire format is already
/// just whatever JSON the bridge emits.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WaMessage {
    pub id: String,
    pub chat_id: String,
    pub from_me: bool,
    pub participant: Option<String>,
    pub timestamp: i64,
    pub push_name: Option<String>,
    #[serde(rename = "type")]
    pub message_type: Option<String>,
    pub text: Option<String>,
    pub quoted_id: Option<String>,
    pub media: Option<String>,
    pub mime_type: Option<String>,
    pub caption: Option<String>,
    pub thumbnail_base64: Option<String>,
    pub is_voice_note: Option<bool>,
    pub seconds: Option<i64>,
    pub file_name: Option<String>,
    pub file_length: Option<i64>,
    pub reaction: Option<WaReaction>,
    pub unsupported: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WaReceiptUpdate {
    pub id: String,
    pub chat_id: String,
    pub status: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WaMediaResult {
    pub message_id: String,
    pub data_base64: String,
    pub mime_type: String,
}

/// In-memory only — Baileys' own `--session-dir` (under the app data dir, passed at spawn
/// time) is what actually persists the linked-device session across restarts. This struct is
/// just a live cache of what's arrived from the bridge this run, same role `MessageState`
/// plays for SMS.
#[derive(Default)]
pub struct WhatsappState {
    pub link_status: LinkStatus,
    pub qr: Option<String>,
    pub chats: Vec<WaChat>,
    pub contact_names: HashMap<String, String>,
    /// Newest-last per chat, capped at 500 messages/chat so a long session doesn't grow
    /// unbounded — matches the bridge's own `MESSAGE_CACHE_LIMIT` capping in spirit.
    pub messages_by_chat: HashMap<String, Vec<WaMessage>>,
    pub media_cache: HashMap<String, (String, String)>, // messageId -> (dataBase64, mimeType)
    /// Raw Baileys `WAMessageStatus` code per message id (roughly: 1 pending, 2 sent,
    /// 3 delivered, 4 read, 5 played) — left as the wire code rather than an enum since the
    /// frontend already renders WhatsApp-style ticks off this exact numbering.
    pub delivery_status: HashMap<String, i64>,
}

const MAX_MESSAGES_PER_CHAT: usize = 500;

impl WhatsappState {
    pub fn upsert_chats(&mut self, chats: Vec<WaChat>) {
        for chat in chats {
            if let Some(existing) = self.chats.iter_mut().find(|c| c.id == chat.id) {
                *existing = chat;
            } else {
                self.chats.push(chat);
            }
        }
        self.chats.sort_by(|a, b| {
            b.conversation_timestamp
                .unwrap_or(0)
                .cmp(&a.conversation_timestamp.unwrap_or(0))
        });
    }

    pub fn upsert_contacts(&mut self, contacts: Vec<(String, Option<String>)>) {
        for (id, name) in contacts {
            if let Some(name) = name {
                self.contact_names.insert(id, name);
            }
        }
    }

    pub fn append_messages(&mut self, messages: Vec<WaMessage>) {
        for message in messages {
            let bucket = self.messages_by_chat.entry(message.chat_id.clone()).or_default();
            if let Some(existing) = bucket.iter_mut().find(|m| m.id == message.id) {
                *existing = message;
            } else {
                bucket.push(message);
                bucket.sort_by_key(|m| m.timestamp);
                if bucket.len() > MAX_MESSAGES_PER_CHAT {
                    let overflow = bucket.len() - MAX_MESSAGES_PER_CHAT;
                    bucket.drain(0..overflow);
                }
            }
        }
    }

    pub fn apply_receipts(&mut self, updates: Vec<WaReceiptUpdate>) {
        for update in updates {
            if let Some(status) = update.status {
                self.delivery_status.insert(update.id, status);
            }
        }
    }
}
