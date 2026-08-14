//! Wire-protocol types. Most structs here aren't used until later phases (Notifications,
//! Messages, Files, etc.) — only the handshake types are exercised in Phase A.
#![allow(dead_code)]

pub mod envelope;
