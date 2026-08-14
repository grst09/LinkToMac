import { create } from "zustand";
import { listen } from "@tauri-apps/api/event";

export type ConnectionStatus = "idle" | "connected";

interface PairedEvent {
  device_id: string;
  device_name: string;
  is_new_pairing: boolean;
}

interface DisconnectedEvent {
  device_id: string;
}

interface ConnectionState {
  status: ConnectionStatus;
  deviceId: string | null;
  deviceName: string | null;
  /** Bumped on every paired/disconnected event so components (e.g. the paired-devices list)
   *  know to re-fetch from the backend rather than needing their own event wiring. */
  revision: number;
}

export const useConnectionStore = create<ConnectionState>(() => ({
  status: "idle",
  deviceId: null,
  deviceName: null,
  revision: 0,
}));

let initialized = false;

/** Call once at app startup. Not done at module scope because Tauri's event system needs the
 *  webview to be ready — App.tsx's top-level effect is the right place. */
export function initConnectionListeners() {
  if (initialized) return;
  initialized = true;

  listen<PairedEvent>("paired", (event) => {
    useConnectionStore.setState((s) => ({
      status: "connected",
      deviceId: event.payload.device_id,
      deviceName: event.payload.device_name,
      revision: s.revision + 1,
    }));
  });

  listen<DisconnectedEvent>("disconnected", (event) => {
    useConnectionStore.setState((s) => {
      if (s.deviceId !== event.payload.device_id) return s;
      return { status: "idle", deviceId: null, deviceName: null, revision: s.revision + 1 };
    });
  });
}
