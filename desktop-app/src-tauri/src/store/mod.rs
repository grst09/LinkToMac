//! `PairedDeviceStore`'s device-management methods (remove, list, lookup by id) aren't
//! exercised until Phase B's paired-device UI.
#![allow(dead_code)]

pub mod identity;
pub mod local_contacts;
pub mod local_notes;
pub mod paired_devices;
pub mod pending_messages;
pub mod pending_note_mutations;
pub mod settings;
