//! SMS thread state, ported from `Storage/MessageStore.swift`. Full-snapshot-on-change (see
//! docs/PROTOCOL.md) plus the "local-only" echo-thread handling for numbers Android's
//! `content://sms` never records a LinkToMac-sent message against — see the doc comment on
//! `MessageState::add_local_message` for why that's a real Android restriction, not a bug.

use crate::protocol::envelope::{SmsMessage, SmsThread};

const LOCAL_THREAD_PREFIX: &str = "local:";

#[derive(Default)]
pub struct MessageState {
    threads: Vec<SmsThread>,
    local_only_threads: Vec<SmsThread>,
}

impl MessageState {
    /// Replaces the real thread list wholesale (matches Android's full-snapshot `sms.sync`
    /// model) and drops any local-only thread whose address now has a real one, sorted
    /// newest-message-first exactly like `MessageStore.update`.
    pub fn update(&mut self, mut threads: Vec<SmsThread>) {
        threads.sort_by(|a, b| last_message_date(b).total_cmp(&last_message_date(a)));
        self.threads = threads;
        let real_addresses: std::collections::HashSet<&str> =
            self.threads.iter().map(|t| t.address.as_str()).collect();
        self.local_only_threads
            .retain(|t| !real_addresses.contains(t.address.as_str()));
    }

    /// Called right after sending the first message of a new conversation, so the UI can show
    /// optimistic confirmation immediately rather than waiting on a sync that will never
    /// arrive for a number Android doesn't hold the default-SMS-app role for. Returns the
    /// synthetic thread id.
    pub fn add_local_message(&mut self, address: &str, body: &str, now_millis: f64) -> String {
        let thread_id = Self::local_thread_id(address);
        let message = SmsMessage {
            id: uuid::Uuid::new_v4().to_string(),
            address: address.to_string(),
            body: body.to_string(),
            date: now_millis,
            is_outgoing: true,
        };
        if let Some(existing) = self
            .local_only_threads
            .iter_mut()
            .find(|t| t.thread_id == thread_id)
        {
            existing.messages.push(message);
        } else {
            self.local_only_threads.push(SmsThread {
                thread_id: thread_id.clone(),
                address: address.to_string(),
                contact_name: None,
                messages: vec![message],
            });
        }
        thread_id
    }

    /// What the UI should actually render — real threads plus any local-only ones whose
    /// address doesn't (yet) have a real thread, merged and sorted by most recent message.
    pub fn all_threads(&self) -> Vec<SmsThread> {
        let real_addresses: std::collections::HashSet<&str> =
            self.threads.iter().map(|t| t.address.as_str()).collect();
        let mut merged: Vec<SmsThread> = self.threads.clone();
        merged.extend(
            self.local_only_threads
                .iter()
                .filter(|t| !real_addresses.contains(t.address.as_str()))
                .cloned(),
        );
        merged.sort_by(|a, b| last_message_date(b).total_cmp(&last_message_date(a)));
        merged
    }

    pub fn local_thread_id(address: &str) -> String {
        format!("{LOCAL_THREAD_PREFIX}{address}")
    }
}

fn last_message_date(thread: &SmsThread) -> f64 {
    thread.messages.last().map(|m| m.date).unwrap_or(0.0)
}
