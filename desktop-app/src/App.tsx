import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import QRCode from "qrcode";
import "./App.css";

// Phase A verification page only — proves the Rust backend's handshake works end to end
// (pairing QR -> Android scans -> paired). The real, animated/icon-rich UI described in the
// Tauri-rewrite plan is Phase B+ scope, built on top of this once the backend is proven out.

interface PairingQrPayload {
  host: string;
  port: number;
  pairingToken: string;
  macDeviceId: string;
}

interface PairedEvent {
  device_id: string;
  device_name: string;
  is_new_pairing: boolean;
}

function App() {
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [payload, setPayload] = useState<PairingQrPayload | null>(null);
  const [pairedEvents, setPairedEvents] = useState<PairedEvent[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const unlisten = listen<PairedEvent>("paired", (event) => {
      setPairedEvents((prev) => [event.payload, ...prev]);
    });
    return () => {
      unlisten.then((f) => f());
    };
  }, []);

  async function beginPairing() {
    setError(null);
    try {
      const result = await invoke<PairingQrPayload>("begin_pairing");
      setPayload(result);
      const dataUrl = await QRCode.toDataURL(JSON.stringify(result), {
        width: 280,
        margin: 2,
      });
      setQrDataUrl(dataUrl);
    } catch (e) {
      setError(String(e));
    }
  }

  return (
    <main className="container">
      <h1>LinkToMac — Phase A</h1>
      <p>Scan this QR with the phone's LinkToMac app to pair.</p>

      <button onClick={beginPairing}>
        {payload ? "Generate new QR" : "Pair Device"}
      </button>

      {error && <p style={{ color: "red" }}>{error}</p>}

      {qrDataUrl && (
        <div style={{ marginTop: 16 }}>
          <img src={qrDataUrl} alt="Pairing QR code" />
          <p>
            <code>
              {payload?.host}:{payload?.port}
            </code>
          </p>
        </div>
      )}

      <h2>Connection log</h2>
      {pairedEvents.length === 0 ? (
        <p>No connections yet.</p>
      ) : (
        <ul style={{ textAlign: "left" }}>
          {pairedEvents.map((e, i) => (
            <li key={i}>
              <strong>{e.device_name}</strong> ({e.device_id}) —{" "}
              {e.is_new_pairing ? "new pairing" : "reconnect"}
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}

export default App;
