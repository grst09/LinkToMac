import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

/** Mirrors the phone's `SyncCategory` toggles (see `AppSettingsStore.kt` on Android and
 *  `SyncSettingsPayload` in envelope.rs) — pushed on every connect and whenever a toggle
 *  changes, so the Mac knows the current state without waiting for a write to be rejected. */
export interface SyncSettings {
  notificationsEnabled: boolean;
  callsAndMessagesEnabled: boolean;
  contactsEnabled: boolean;
  photosEnabled: boolean;
  notesEnabled: boolean;
  clipboardEnabled: boolean;
}

const defaultSyncSettings: SyncSettings = {
  notificationsEnabled: true,
  callsAndMessagesEnabled: true,
  contactsEnabled: true,
  photosEnabled: true,
  notesEnabled: true,
  clipboardEnabled: true,
};

interface SyncSettingsState {
  settings: SyncSettings;
}

export const useSyncSettingsStore = create<SyncSettingsState>(() => ({
  settings: defaultSyncSettings,
}));

let initialized = false;

export function initSyncSettingsListeners() {
  if (initialized) return;
  initialized = true;

  invoke<SyncSettings>("get_sync_settings").then((settings) => {
    useSyncSettingsStore.setState({ settings });
  });

  listen<SyncSettings>("sync-settings-updated", (event) => {
    useSyncSettingsStore.setState({ settings: event.payload });
  });
}
