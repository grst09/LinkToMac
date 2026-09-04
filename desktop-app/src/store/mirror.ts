import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface MirrorConfig {
  width: number;
  height: number;
  fps: number;
}

export interface AppInfo {
  packageName: string;
  appName: string;
  iconBase64: string;
}

interface MirrorStoreState {
  isActive: boolean;
  config: MirrorConfig | null;
  stoppedReason: string | null;
  apps: AppInfo[];
  loaded: boolean;
}

export const useMirrorStore = create<MirrorStoreState>(() => ({
  isActive: false,
  config: null,
  stoppedReason: null,
  apps: [],
  loaded: false,
}));

export async function requestMirrorApps() {
  await invoke("request_mirror_apps");
}

export async function launchMirrorApp(packageName: string) {
  await invoke("launch_mirror_app", { packageName });
}

let initialized = false;

export function initMirrorListeners() {
  if (initialized) return;
  initialized = true;

  invoke<{
    isActive: boolean;
    config: MirrorConfig | null;
    stoppedReason: string | null;
    apps: AppInfo[];
  }>("get_mirror_state").then((s) => {
    useMirrorStore.setState({
      isActive: s.isActive,
      config: s.config,
      stoppedReason: s.stoppedReason,
      apps: s.apps,
      loaded: true,
    });
  });

  listen<MirrorConfig>("mirror-config", (event) => {
    useMirrorStore.setState({ isActive: true, config: event.payload, stoppedReason: null });
  });

  listen<{ reason: string }>("mirror-stopped", (event) => {
    useMirrorStore.setState({ isActive: false, config: null, stoppedReason: event.payload.reason });
  });

  listen<AppInfo[]>("mirror-apps-updated", (event) => {
    useMirrorStore.setState({ apps: event.payload });
  });
}
