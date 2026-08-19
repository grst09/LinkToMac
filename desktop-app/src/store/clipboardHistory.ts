import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface ClipboardEntry {
  id: string;
  text: string;
  source: "mac" | "android";
  timestamp: number;
}

interface ClipboardHistoryState {
  entries: ClipboardEntry[];
  loaded: boolean;
}

export const useClipboardHistoryStore = create<ClipboardHistoryState>(() => ({
  entries: [],
  loaded: false,
}));

export async function copyClipboardEntry(text: string) {
  await invoke("copy_clipboard_entry", { text });
}

export async function clearClipboardHistory() {
  await invoke("clear_clipboard_history");
  useClipboardHistoryStore.setState({ entries: [] });
}

let initialized = false;

export function initClipboardHistoryListeners() {
  if (initialized) return;
  initialized = true;

  invoke<ClipboardEntry[]>("list_clipboard_history").then((entries) => {
    useClipboardHistoryStore.setState({ entries, loaded: true });
  });

  listen<ClipboardEntry[]>("clipboard-history-updated", (event) => {
    useClipboardHistoryStore.setState({ entries: event.payload, loaded: true });
  });
}
