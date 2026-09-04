//! User-configurable app preferences, persisted the same way as `paired_devices.rs` (a single
//! JSON file in the app data dir) — no migration concerns here since this is new, Tauri-only
//! state with nothing in the old Swift app to import.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

/// Trade-off knob for screen mirroring's decode→IPC pipeline (see `mirror.rs`'s doc comments
/// for why this exists at all — full-resolution frames at 30fps are far more data than Tauri's
/// IPC channel can move in real time). Each level maps to a downscale cap and a forwarded-frame
/// rate cap; picked at `mirror.config` time, so changing this mid-session only affects the
/// *next* mirroring session, not one already in progress.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MirrorQuality {
    Performance,
    Balanced,
    Quality,
}

impl MirrorQuality {
    pub fn max_display_long_edge(self) -> usize {
        match self {
            Self::Performance => 640,
            Self::Balanced => 960,
            Self::Quality => 1280,
        }
    }

    pub fn min_forward_interval(self) -> std::time::Duration {
        let fps: u64 = match self {
            Self::Performance => 12,
            Self::Balanced => 20,
            Self::Quality => 30,
        };
        std::time::Duration::from_millis(1000 / fps)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppSettings {
    /// Native OS banner popups for mirrored notifications — off just means they stop showing
    /// as banners; the in-app Notifications list is unaffected either way.
    pub show_notification_banners: bool,
    pub clipboard_sync_enabled: bool,
    pub mirror_quality: MirrorQuality,
    /// Client-side pre-check on uploads (Mac → phone) before ever sending a byte — see
    /// `commands::files::upload_file`. Downloads have no equivalent check, matching the old
    /// Swift app (see docs/PLAN.md's Phase D notes) — Android's own
    /// `FileRepository.MAX_TRANSFER_BYTES` is the only gate on that direction. Both are capped at
    /// 8MB: every transfer is base64'd twice on the wire (once for the JSON payload field, again
    /// when the encrypted envelope is serialized — see `crypto::secure_channel::seal`), roughly
    /// 1.78x inflation, and OkHttp's WebSocket send queue on the Android side silently drops
    /// (doesn't error) any single outgoing message over 16MB. This can only be lowered from the
    /// frontend, never raised past what Android will actually accept without also raising
    /// `MAX_TRANSFER_BYTES` there.
    pub max_transfer_mb: u64,
    /// Whether the Mac advertises itself over mDNS and accepts new incoming connections at all —
    /// see `net::server::run`'s doc comment. Off means a disconnected Mac stays fully closed
    /// (no open port, not discoverable) instead of always listening for a reconnect. Defaults to
    /// on so existing behavior — and first-run pairing — isn't broken by this setting's addition.
    #[serde(default = "default_discovery_enabled")]
    pub discovery_enabled: bool,
}

fn default_discovery_enabled() -> bool {
    true
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            show_notification_banners: true,
            clipboard_sync_enabled: true,
            mirror_quality: MirrorQuality::Balanced,
            max_transfer_mb: 8,
            discovery_enabled: true,
        }
    }
}

/// Matches Android's `FileRepository.MAX_TRANSFER_BYTES` — see `AppSettings::max_transfer_mb`'s
/// doc comment for why. Enforced here too (not just as the default and the frontend's input
/// max) so a `max_transfer_mb` persisted from before this ceiling was lowered — anyone who's run
/// an earlier build — gets clamped back down to something Android will actually accept, instead
/// of silently keeping a stale, now-unsafe value forever.
const MAX_TRANSFER_MB_CEILING: u64 = 8;

pub struct SettingsStore {
    file_path: PathBuf,
    settings: AppSettings,
}

impl SettingsStore {
    pub fn load_or_create(app_data_dir: &Path) -> std::io::Result<Self> {
        std::fs::create_dir_all(app_data_dir)?;
        let file_path = app_data_dir.join("settings.json");
        let mut settings = Self::try_load(&file_path).unwrap_or_default();
        settings.max_transfer_mb = settings.max_transfer_mb.clamp(1, MAX_TRANSFER_MB_CEILING);
        Ok(Self { file_path, settings })
    }

    fn try_load(path: &Path) -> Option<AppSettings> {
        let data = std::fs::read_to_string(path).ok()?;
        serde_json::from_str(&data).ok()
    }

    fn save(&self) -> std::io::Result<()> {
        let data = serde_json::to_string(&self.settings)?;
        std::fs::write(&self.file_path, data)
    }

    pub fn get(&self) -> AppSettings {
        self.settings.clone()
    }

    pub fn update(&mut self, settings: AppSettings) -> std::io::Result<()> {
        self.settings = settings;
        self.save()
    }
}
