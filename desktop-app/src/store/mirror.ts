import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface MirrorConfig {
  width: number;
  height: number;
  fps: number;
}

interface MirrorStoreState {
  isActive: boolean;
  config: MirrorConfig | null;
  stoppedReason: string | null;
  loaded: boolean;
}

export const useMirrorStore = create<MirrorStoreState>(() => ({
  isActive: false,
  config: null,
  stoppedReason: null,
  loaded: false,
}));

let initialized = false;

export function initMirrorListeners() {
  if (initialized) return;
  initialized = true;

  invoke<{ isActive: boolean; config: MirrorConfig | null; stoppedReason: string | null }>(
    "get_mirror_state",
  ).then((s) => {
    useMirrorStore.setState({
      isActive: s.isActive,
      config: s.config,
      stoppedReason: s.stoppedReason,
      loaded: true,
    });
  });

  listen<MirrorConfig>("mirror-config", (event) => {
    useMirrorStore.setState({ isActive: true, config: event.payload, stoppedReason: null });
  });

  listen<{ reason: string }>("mirror-stopped", (event) => {
    useMirrorStore.setState({ isActive: false, config: null, stoppedReason: event.payload.reason });
  });
}
