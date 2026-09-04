//! Screen-mirroring decode pipeline (Phase E). Conceptually ports
//! `mac-app/Sources/LinkToMac/Storage/MirrorStore.swift` + `Video/H264Parser.swift`, but decodes
//! in Rust via the `openh264` crate rather than VideoToolbox: Tauri doesn't bundle a fixed
//! browser engine (WebKitGTK on Linux, not just Safari's WebKit), so WebCodecs availability
//! can't be assumed the way it can in the old Mac-only app. See docs/PLAN.md's Phase E notes.

use std::sync::{Arc, Condvar, Mutex};
use std::time::Instant;

use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use openh264::decoder::Decoder;
use openh264::formats::YUVSource;
use openh264::nal_units;
use tauri::ipc::{Channel, InvokeResponseBody};

use crate::protocol::envelope::{AppInfo, MirrorConfigPayload};
use crate::store::settings::MirrorQuality;

// The Performance/Balanced/Quality downscale + forward-rate caps live on `MirrorQuality` itself
// (store/settings.rs) — every decoded frame here still has to cross the Tauri IPC boundary as
// raw RGBA, and a modern phone's native resolution (e.g. ~1800x2200 on a foldable) is ~15MB/frame
// at 4 bytes/pixel, so downscaling + rate-capping here is a display-pipeline decision independent
// of the phone's capture quality (which stays at native resolution — see docs/PLAN.md's Phase 4
// notes on why that cap was removed; LAN bandwidth isn't the constraint there).

#[derive(Default)]
pub struct MirrorState {
    pub is_active: bool,
    pub config: Option<MirrorConfigPayload>,
    pub stopped_reason: Option<String>,
    /// Registered by `start_mirroring`, cleared by `stop_mirroring` — read once when a mirroring
    /// session's decode thread spawns (see `configure`), not touched by that thread's Mutex.
    frame_channel: Option<Channel<InvokeResponseBody>>,
    /// Send half of the current session's frame handoff — see `FrameSlot`. `None` when no
    /// decode thread is running.
    frame_slot: Option<FrameSlot>,
    /// The phone's launchable-apps list for the mirroring app-grid — requested once
    /// `mirror.config` arrives (see `dispatch::mirror::config`), refreshed on demand via the
    /// `request_mirror_apps` command.
    pub apps: Vec<AppInfo>,
}

impl MirrorState {
    pub fn set_channel(&mut self, channel: Channel<InvokeResponseBody>) {
        self.frame_channel = Some(channel);
    }

    pub fn clear_channel(&mut self) {
        self.frame_channel = None;
    }

    pub fn set_apps(&mut self, apps: Vec<AppInfo>) {
        self.apps = apps;
    }

    /// Builds a fresh decoder, primes it with the SPS/PPS parameter sets from `mirror.config`,
    /// and spawns a dedicated OS thread to own it for the rest of the session.
    ///
    /// This *must* run off any tokio task. Decoding + RGBA conversion is real CPU work (tens of
    /// milliseconds per frame is enough to matter at 30fps); doing it inline in the connection's
    /// async read loop — which is also the loop that answers the phone's `ping` keepalive with
    /// `pong` — starves that loop the moment decode falls behind even slightly. In practice this
    /// silently killed the connection: Android's ~45s keepalive watchdog fired because `pong`
    /// never got sent in time, and the whole session dropped ("Connection reset without closing
    /// handshake"), not just the video. Moving decode to its own thread, fed through
    /// `FrameSlot::put` (which is O(1) and never blocks), keeps the read loop's ability to
    /// service `ping`/control messages completely independent of decoder speed.
    ///
    /// SPS/PPS arrive without Annex-B start codes (see docs/PROTOCOL.md — Android strips them
    /// for this payload the same way it does for the Mac's
    /// `CMVideoFormatDescriptionCreateFromH264ParameterSets` call), so start codes are added
    /// back before handing them to the decoder. Priming here means the decoder already has
    /// parameter sets before the first video binary frame arrives, rather than relying on
    /// openh264's "first few frames may fail" fallback behavior.
    pub fn configure(&mut self, payload: MirrorConfigPayload, quality: MirrorQuality) -> anyhow::Result<()> {
        let sps = BASE64.decode(&payload.sps_base64)?;
        let pps = BASE64.decode(&payload.pps_base64)?;
        let mut decoder = Decoder::new()?;
        let _ = decoder.decode(&annex_b(&sps));
        let _ = decoder.decode(&annex_b(&pps));

        if let Some(slot) = self.frame_slot.take() {
            slot.close();
        }
        let slot = FrameSlot::new();
        let worker_slot = slot.clone();
        let channel = self.frame_channel.clone();
        std::thread::spawn(move || decode_worker(decoder, worker_slot, channel, quality));
        self.frame_slot = Some(slot);

        self.config = Some(payload);
        self.is_active = true;
        self.stopped_reason = None;
        Ok(())
    }

    pub fn stopped(&mut self, reason: String) {
        self.is_active = false;
        self.stopped_reason = Some(reason);
        self.config = None;
        if let Some(slot) = self.frame_slot.take() {
            slot.close();
        }
    }

    /// Hands a decrypted binary frame off to the decode thread, if a session is active.
    /// Non-blocking and effectively instant (a mutex lock + a value swap) regardless of how far
    /// behind the decoder is — see `FrameSlot::put`. This is what the connection's read loop
    /// calls directly, so it must never itself become slow.
    pub fn submit_frame(&self, data: Vec<u8>) {
        if let Some(slot) = &self.frame_slot {
            slot.put(data);
        }
    }
}

struct FrameSlotState {
    data: Option<Vec<u8>>,
    closed: bool,
}

/// A single-slot, latest-value-wins handoff between the async connection task (producer) and
/// the decode thread (consumer). Deliberately not a queue: if the decoder falls behind, the
/// right behavior is to skip ahead to the newest frame once it's ready, not to grind through a
/// growing backlog of stale ones (which just makes the picture fall further behind real time —
/// the "stuck" symptom this replaced). A real H.264 stream tolerates this: skipping frames
/// between periodic keyframes causes brief artifacts at worst, self-healing at the next one.
#[derive(Clone)]
struct FrameSlot(Arc<(Mutex<FrameSlotState>, Condvar)>);

impl FrameSlot {
    fn new() -> Self {
        Self(Arc::new((
            Mutex::new(FrameSlotState { data: None, closed: false }),
            Condvar::new(),
        )))
    }

    fn put(&self, data: Vec<u8>) {
        let (lock, cvar) = &*self.0;
        let mut state = lock.lock().unwrap();
        state.data = Some(data);
        cvar.notify_one();
    }

    /// Blocks the calling thread until a frame is available or the slot is closed (`None`).
    fn take_blocking(&self) -> Option<Vec<u8>> {
        let (lock, cvar) = &*self.0;
        let mut state = lock.lock().unwrap();
        loop {
            if let Some(data) = state.data.take() {
                return Some(data);
            }
            if state.closed {
                return None;
            }
            state = cvar.wait(state).unwrap();
        }
    }

    fn close(&self) {
        let (lock, cvar) = &*self.0;
        lock.lock().unwrap().closed = true;
        cvar.notify_one();
    }
}

/// Runs on its own OS thread for the lifetime of one mirroring session (see `configure`'s doc
/// comment for why). Owns the decoder exclusively — no locking needed around it.
fn decode_worker(
    mut decoder: Decoder,
    slot: FrameSlot,
    channel: Option<Channel<InvokeResponseBody>>,
    quality: MirrorQuality,
) {
    let mut last_forwarded_at: Option<Instant> = None;
    let max_long_edge = quality.max_display_long_edge();
    let min_forward_interval = quality.min_forward_interval();

    while let Some(data) = slot.take_blocking() {
        for packet in nal_units(&data) {
            match decoder.decode(packet) {
                Ok(Some(yuv)) => {
                    let Some(channel) = &channel else { continue };

                    let now = Instant::now();
                    let should_send = last_forwarded_at
                        .is_none_or(|t| now.duration_since(t) >= min_forward_interval);
                    if !should_send {
                        continue;
                    }
                    last_forwarded_at = Some(now);

                    let (width, height) = yuv.dimensions();
                    let mut full_res = vec![0u8; yuv.rgba8_len()];
                    yuv.write_rgba8(&mut full_res);
                    let (scaled, out_width, out_height) =
                        downscale_rgba(&full_res, width, height, max_long_edge);

                    // Self-describing payload — an 8-byte [width, height] (u32 LE) header
                    // followed by RGBA bytes — since the frontend can't otherwise know the
                    // post-downscale dimensions of any given frame.
                    let mut payload = Vec::with_capacity(8 + scaled.len());
                    payload.extend_from_slice(&(out_width as u32).to_le_bytes());
                    payload.extend_from_slice(&(out_height as u32).to_le_bytes());
                    payload.extend_from_slice(&scaled);

                    if let Err(e) = channel.send(InvokeResponseBody::Raw(payload)) {
                        tracing::warn!("failed to send mirror frame to frontend: {}", e);
                    }
                }
                Ok(None) => {}
                // Isolated decode errors are expected (frames skipped by `FrameSlot`'s
                // overwrite semantics can desync a GOP until the next keyframe) — matches
                // openh264's own guidance to keep decoding past them rather than abort.
                Err(e) => tracing::debug!("mirror frame decode error (continuing): {}", e),
            }
        }
    }
}

/// Re-adds the 4-byte Annex-B start code stripped from `mirror.config`'s SPS/PPS payloads.
fn annex_b(nal_payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(nal_payload.len() + 4);
    out.extend_from_slice(&[0, 0, 0, 1]);
    out.extend_from_slice(nal_payload);
    out
}

/// Nearest-neighbor downscale of an RGBA buffer so its long edge is at most `max_long_edge`.
/// No-op (aside from the copy) if the source is already within bounds.
fn downscale_rgba(rgba: &[u8], width: usize, height: usize, max_long_edge: usize) -> (Vec<u8>, usize, usize) {
    let long_edge = width.max(height);
    if long_edge <= max_long_edge || long_edge == 0 {
        return (rgba.to_vec(), width, height);
    }

    let scale = max_long_edge as f64 / long_edge as f64;
    let out_width = ((width as f64) * scale).round().max(1.0) as usize;
    let out_height = ((height as f64) * scale).round().max(1.0) as usize;

    let mut out = vec![0u8; out_width * out_height * 4];
    for oy in 0..out_height {
        let sy = (((oy as f64 + 0.5) / scale) as usize).min(height - 1);
        let src_row = sy * width;
        let dst_row = oy * out_width;
        for ox in 0..out_width {
            let sx = (((ox as f64 + 0.5) / scale) as usize).min(width - 1);
            let src_idx = (src_row + sx) * 4;
            let dst_idx = (dst_row + ox) * 4;
            out[dst_idx..dst_idx + 4].copy_from_slice(&rgba[src_idx..src_idx + 4]);
        }
    }
    (out, out_width, out_height)
}
