import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";

export type MirrorQuality = "performance" | "balanced" | "quality";

export interface AppSettings {
  showNotificationBanners: boolean;
  clipboardSyncEnabled: boolean;
  mirrorQuality: MirrorQuality;
  maxTransferMb: number;
}

interface StorageInfo {
  file_count: number;
  total_bytes: number;
}

interface SettingsState {
  settings: AppSettings | null;
  launchAtLogin: boolean;
  storageInfo: StorageInfo | null;
  appVersion: string | null;
  loaded: boolean;
}

export const useSettingsStore = create<SettingsState>(() => ({
  settings: null,
  launchAtLogin: false,
  storageInfo: null,
  appVersion: null,
  loaded: false,
}));

export async function loadSettings() {
  const [settings, launchAtLogin, storageInfo, appVersion] = await Promise.all([
    invoke<AppSettings>("get_settings"),
    invoke<boolean>("get_launch_at_login"),
    invoke<StorageInfo>("get_storage_info"),
    invoke<string>("get_app_version"),
  ]);
  useSettingsStore.setState({ settings, launchAtLogin, storageInfo, appVersion, loaded: true });
}

/** Optimistic — writes the merged settings locally, then persists. */
export async function updateSettings(partial: Partial<AppSettings>) {
  const current = useSettingsStore.getState().settings;
  if (!current) return;
  const next = { ...current, ...partial };
  useSettingsStore.setState({ settings: next });
  await invoke("update_settings", { settings: next });
}

export async function setLaunchAtLogin(enabled: boolean) {
  useSettingsStore.setState({ launchAtLogin: enabled });
  await invoke("set_launch_at_login", { enabled });
}

export async function clearDownloadedFiles() {
  await invoke("clear_downloaded_files");
  const storageInfo = await invoke<StorageInfo>("get_storage_info");
  useSettingsStore.setState({ storageInfo });
}
