import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export type CallType = "incoming" | "outgoing" | "missed" | "rejected" | "blocked" | "voicemail" | "unknown";

export interface CallLogEntry {
  id: string;
  number: string;
  contactName?: string | null;
  type: CallType;
  date: number;
  durationSeconds: number;
}

interface CallsState {
  calls: CallLogEntry[];
  loaded: boolean;
}

export const useCallsStore = create<CallsState>(() => ({
  calls: [],
  loaded: false,
}));

let initialized = false;

export function initCallsListeners() {
  if (initialized) return;
  initialized = true;

  invoke<CallLogEntry[]>("list_calls").then((calls) => {
    useCallsStore.setState({ calls, loaded: true });
  });

  listen<CallLogEntry[]>("calls-updated", (event) => {
    useCallsStore.setState({ calls: event.payload, loaded: true });
  });
}
