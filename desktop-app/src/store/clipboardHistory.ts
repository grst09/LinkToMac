import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface ClipboardEntry {
  id: string;
  text: string;
  source: "mac" | "android";
  timestamp: number;
  isPinned: boolean;
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

/** Optimistic, then re-sorts locally (pinned-first) to match what the backend's `ordered()` will
 *  emit right behind it — avoids a visible flash where the toggled row hasn't moved yet. */
export async function setClipboardEntryPinned(id: string, pinned: boolean) {
  useClipboardHistoryStore.setState((s) => {
    const updated = s.entries.map((e) => (e.id === id ? { ...e, isPinned: pinned } : e));
    const stillPinned = updated.filter((e) => e.isPinned);
    const rest = updated.filter((e) => !e.isPinned);
    return { entries: [...stillPinned, ...rest] };
  });
  await invoke("set_clipboard_entry_pinned", { id, pinned });
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
