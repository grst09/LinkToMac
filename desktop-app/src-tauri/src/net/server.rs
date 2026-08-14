//! WebSocket server + Bonjour/mDNS advertisement + handshake, ported from
//! `mac-app/Sources/LinkToMac/Networking/ConnectionServer.swift`. See docs/PROTOCOL.md.
//!
//! Phase A scope: prove an already-paired Android phone reconnects to this server exactly as
//! it did to the old Swift app (same identity key, same pin, no forced re-pair). Feature
//! message dispatch (notifications, calls, files, etc.) is later-phase work — this module logs
//! and otherwise ignores anything past the handshake for now, except `ping`/`pong` keepalive.

use std::net::SocketAddr;
use std::sync::Arc;

use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use futures_util::{SinkExt, StreamExt};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use serde::Serialize;
use tauri::Emitter;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tokio_tungstenite::WebSocketStream;

use crate::crypto::secure_channel::{self, SecureChannelError};
use crate::net::local_network;
use crate::protocol::envelope::{
    EmptyPayload, EncryptedFrame, HelloAckPayload, HelloPayload, Message, PairingQrPayload,
    ServerHelloPayload,
};
use crate::store::identity::IdentityStore;
use crate::store::paired_devices::PairedDeviceStore;

pub const PORT: u16 = 53821;
const SERVICE_TYPE: &str = "_linktomac._tcp.local.";

pub struct AppState {
    pub identity: IdentityStore,
    pub paired_devices: Mutex<PairedDeviceStore>,
    /// Single pending pairing session at a time, matching the old app's behavior — see
    /// `beginNewPairingSession`/`activePairingToken` in `ConnectionServer.swift`.
    pub active_pairing_token: Mutex<Option<Vec<u8>>>,
    app_handle: tauri::AppHandle,
    _mdns: ServiceDaemon,
}

impl AppState {
    pub fn new(
        identity: IdentityStore,
        paired_devices: PairedDeviceStore,
        app_handle: tauri::AppHandle,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        let mdns = start_mdns(&identity)?;
        Ok(Self {
            identity,
            paired_devices: Mutex::new(paired_devices),
            active_pairing_token: Mutex::new(None),
            app_handle,
            _mdns: mdns,
        })
    }
}

#[derive(Serialize, Clone)]
struct PairedEvent {
    device_id: String,
    device_name: String,
    is_new_pairing: bool,
}

/// Tauri command: starts a new pairing session (single-use token, matching
/// `beginNewPairingSession` in `ConnectionServer.swift`) and returns the payload the frontend
/// renders as a QR code for Android to scan.
#[tauri::command]
pub async fn begin_pairing(
    state: tauri::State<'_, Arc<AppState>>,
) -> Result<PairingQrPayload, String> {
    use aes_gcm::aead::Generate;
    let token_bytes = <[u8; 16] as Generate>::generate();
    *state.active_pairing_token.lock().await = Some(token_bytes.to_vec());

    let host = local_network::primary_ipv4_address()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|| "0.0.0.0".to_string());

    Ok(PairingQrPayload {
        host,
        port: PORT,
        pairing_token: BASE64.encode(token_bytes),
        mac_device_id: state.identity.device_id.clone(),
    })
}

fn start_mdns(identity: &IdentityStore) -> Result<ServiceDaemon, Box<dyn std::error::Error>> {
    let daemon = ServiceDaemon::new()?;

    let raw_host = hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "LinkToMac".to_string());
    let dns_safe_host = sanitize_for_dns(&raw_host);
    let host_name = format!("{dns_safe_host}.local.");

    let pk = identity.public_key_base64();
    let id = identity.device_id.clone();
    let properties: [(&str, &str); 2] = [("pk", pk.as_str()), ("id", id.as_str())];

    let service_info = ServiceInfo::new(
        SERVICE_TYPE,
        &raw_host,
        &host_name,
        "",
        PORT,
        &properties[..],
    )?
    .enable_addr_auto();

    tracing::info!(
        "advertising {} as {} ({}), pk={}...",
        SERVICE_TYPE,
        raw_host,
        host_name,
        &pk[..pk.len().min(12)]
    );
    daemon.register(service_info)?;
    Ok(daemon)
}

fn sanitize_for_dns(name: &str) -> String {
    name.chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' { c } else { '-' })
        .collect()
}

fn local_device_name() -> String {
    hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "This computer".to_string())
}

pub async fn run(state: Arc<AppState>) -> std::io::Result<()> {
    let listener = TcpListener::bind(("0.0.0.0", PORT)).await?;
    tracing::info!("WebSocket server listening on 0.0.0.0:{}", PORT);

    loop {
        let (stream, addr) = listener.accept().await?;
        let state = Arc::clone(&state);
        tokio::spawn(async move {
            if let Err(e) = handle_connection(stream, addr, state).await {
                tracing::warn!("connection {} ended: {}", addr, e);
            }
        });
    }
}

type WsWriter = futures_util::stream::SplitSink<WebSocketStream<TcpStream>, WsMessage>;

async fn handle_connection(
    stream: TcpStream,
    addr: SocketAddr,
    state: Arc<AppState>,
) -> anyhow::Result<()> {
    tracing::info!("incoming connection from {}", addr);
    let ws_stream = tokio_tungstenite::accept_async(stream).await?;
    let (mut write, mut read) = ws_stream.split();

    // Step 1: serverHello, unencrypted, immediately — see docs/PROTOCOL.md.
    let server_hello = ServerHelloPayload {
        mac_public_key: state.identity.public_key_base64(),
        mac_device_id: state.identity.device_id.clone(),
        mac_device_name: local_device_name(),
    };
    send_plain(&mut write, "serverHello", &server_hello).await?;

    // Step 2: wait for hello.
    let hello = match read.next().await {
        Some(Ok(WsMessage::Text(text))) => text,
        Some(Ok(other)) => {
            anyhow::bail!("expected text hello, got {:?}", other);
        }
        Some(Err(e)) => return Err(e.into()),
        None => anyhow::bail!("connection closed before hello"),
    };
    let message: Message = serde_json::from_str(&hello)?;
    if message.message_type != "hello" {
        anyhow::bail!("expected hello, got {}", message.message_type);
    }
    let hello: HelloPayload = serde_json::from_value(message.payload)?;
    let device_id_for_log = hello.device_id.clone();

    // Step 3: validate + derive session key, exactly matching handleHello in
    // ConnectionServer.swift (same three-way branch: new pairing / reconnect / reject).
    let outcome = {
        let mut active_token = state.active_pairing_token.lock().await;
        let mut paired = state.paired_devices.lock().await;
        process_hello(&hello, &state.identity, &mut active_token, &mut paired)
    };

    let result = match outcome {
        Ok(Some(handshake)) => handshake,
        Ok(None) => {
            tracing::warn!(
                "rejecting hello from {} (device {}): no valid pairing/device token",
                addr,
                device_id_for_log
            );
            send_reject(&mut write).await?;
            return Ok(());
        }
        Err(e) => {
            tracing::warn!("failed to process hello from {}: {}", addr, e);
            return Ok(());
        }
    };

    // Step 4: encrypted helloAck.
    let ack = HelloAckPayload {
        status: "paired".to_string(),
        device_token: Some(result.device_token.clone()),
        mac_device_name: local_device_name(),
    };
    send_encrypted(&mut write, "helloAck", &ack, &result.session_key).await?;

    tracing::info!(
        "paired: device {} ({}) — {} — no re-pair required",
        device_id_for_log,
        result.device_name,
        if result.is_new_pairing {
            "new pairing"
        } else {
            "reconnect"
        }
    );
    let _ = state.app_handle.emit(
        "paired",
        PairedEvent {
            device_id: device_id_for_log.clone(),
            device_name: result.device_name.clone(),
            is_new_pairing: result.is_new_pairing,
        },
    );

    // Step 5: minimal post-handshake loop for Phase A — decrypt and log; answer ping/pong so
    // Android's 45s keepalive timeout doesn't fire. Full message dispatch (notifications,
    // calls, files, etc.) is later-phase work per the Tauri-rewrite plan.
    while let Some(msg) = read.next().await {
        let msg = msg?;
        match msg {
            WsMessage::Text(text) => {
                if let Err(e) =
                    handle_encrypted_text(&text, &result.session_key, &mut write).await
                {
                    tracing::warn!("failed to handle message from {}: {}", addr, e);
                }
            }
            WsMessage::Binary(data) => {
                match secure_channel::open_raw(&data, &result.session_key) {
                    Ok(plaintext) => {
                        tracing::debug!("received {} bytes of binary (mirror) data", plaintext.len());
                    }
                    Err(e) => tracing::warn!("failed to decrypt binary frame: {}", e),
                }
            }
            WsMessage::Close(_) => break,
            _ => {}
        }
    }

    tracing::info!("disconnected: device {}", device_id_for_log);
    Ok(())
}

async fn handle_encrypted_text(
    text: &str,
    session_key: &[u8; 32],
    write: &mut WsWriter,
) -> anyhow::Result<()> {
    let frame: EncryptedFrame = serde_json::from_str(text)?;
    let plaintext = secure_channel::open(&frame, session_key)?;
    let message: Message = serde_json::from_slice(&plaintext)?;

    if message.message_type == "ping" {
        send_encrypted(write, "pong", &EmptyPayload {}, session_key).await?;
        return Ok(());
    }

    tracing::info!("received {} (dispatch not yet implemented)", message.message_type);
    Ok(())
}

struct HandshakeResult {
    session_key: [u8; 32],
    device_token: String,
    device_name: String,
    is_new_pairing: bool,
}

/// Mirrors `ConnectionServer.handleHello` exactly: `Ok(Some(_))` = paired (new or reconnect),
/// `Ok(None)` = explicit reject (bad/expired token), `Err(_)` = malformed input, logged and
/// dropped without a reply (matches the Swift code's behavior when `deriveSessionKey` throws).
fn process_hello(
    hello: &HelloPayload,
    identity: &IdentityStore,
    active_pairing_token: &mut Option<Vec<u8>>,
    paired: &mut PairedDeviceStore,
) -> Result<Option<HandshakeResult>, SecureChannelError> {
    let (salt, is_new_pairing): (Vec<u8>, bool) =
        if let (Some(pairing_token_b64), Some(active)) =
            (&hello.pairing_token, active_pairing_token.as_ref())
        {
            let token_bytes = BASE64.decode(pairing_token_b64)?;
            if &token_bytes == active {
                (token_bytes, true)
            } else {
                return Ok(None);
            }
        } else if let Some(device_token) = &hello.device_token {
            match paired.device_with_token(device_token) {
                Some(existing) if existing.id == hello.device_id => {
                    let salt = BASE64.decode(&existing.pairing_salt)?;
                    (salt, false)
                }
                _ => return Ok(None),
            }
        } else {
            return Ok(None);
        };

    let session_key = secure_channel::derive_session_key(
        &identity.key_pair.secret_key,
        &hello.android_public_key,
        &salt,
    )?;

    let device_token = if is_new_pairing {
        use aes_gcm::aead::Generate;
        let token_bytes = <[u8; 32] as Generate>::generate();
        BASE64.encode(token_bytes)
    } else {
        hello.device_token.clone().unwrap()
    };

    paired
        .upsert(
            &hello.device_id,
            &hello.device_name,
            &device_token,
            &BASE64.encode(&salt),
        )
        .map_err(|_| SecureChannelError::SealFailed)?;

    if is_new_pairing {
        *active_pairing_token = None;
    }

    Ok(Some(HandshakeResult {
        session_key,
        device_token,
        device_name: hello.device_name.clone(),
        is_new_pairing,
    }))
}

async fn send_plain<T: serde::Serialize>(
    write: &mut WsWriter,
    message_type: &str,
    payload: &T,
) -> anyhow::Result<()> {
    let message = Message {
        message_type: message_type.to_string(),
        payload: serde_json::to_value(payload)?,
    };
    let text = serde_json::to_string(&message)?;
    write.send(WsMessage::Text(text.into())).await?;
    Ok(())
}

async fn send_encrypted<T: serde::Serialize>(
    write: &mut WsWriter,
    message_type: &str,
    payload: &T,
    session_key: &[u8; 32],
) -> anyhow::Result<()> {
    let message = Message {
        message_type: message_type.to_string(),
        payload: serde_json::to_value(payload)?,
    };
    let plaintext = serde_json::to_vec(&message)?;
    let frame = secure_channel::seal(&plaintext, session_key)?;
    let text = serde_json::to_string(&frame)?;
    write.send(WsMessage::Text(text.into())).await?;
    Ok(())
}

async fn send_reject(write: &mut WsWriter) -> anyhow::Result<()> {
    let payload = HelloAckPayload {
        status: "rejected".to_string(),
        device_token: None,
        mac_device_name: local_device_name(),
    };
    send_plain(write, "helloAck", &payload).await?;
    write.close().await?;
    Ok(())
}
