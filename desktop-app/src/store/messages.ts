import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface SmsMessage {
  id: string;
  address: string;
  body: string;
  date: number;
  isOutgoing: boolean;
}

export interface SmsThread {
  threadId: string;
  address: string;
  contactName?: string | null;
  messages: SmsMessage[];
}

interface MessagesState {
  threads: SmsThread[];
  loaded: boolean;
}

export const useMessagesStore = create<MessagesState>(() => ({
  threads: [],
  loaded: false,
}));

/** Matches the "local:" prefix `MessageState::local_thread_id` uses on the Rust side — a
 *  thread that only exists as an optimistic local echo, never confirmed by a real sms.sync. */
export function isLocalOnlyThread(threadId: string): boolean {
  return threadId.startsWith("local:");
}

export async function sendSms(address: string, body: string): Promise<string> {
  const threads = await invoke<SmsThread[]>("send_sms", { address, body });
  useMessagesStore.setState({ threads });
  const threadId =
    threads.find((t) => t.address === address)?.threadId ?? `local:${address}`;
  return threadId;
}

export async function refreshMessages() {
  await invoke("refresh_messages");
}

let initialized = false;

export function initMessagesListeners() {
  if (initialized) return;
  initialized = true;

  invoke<SmsThread[]>("list_threads").then((threads) => {
    useMessagesStore.setState({ threads, loaded: true });
  });

  listen<SmsThread[]>("threads-updated", (event) => {
    useMessagesStore.setState({ threads: event.payload, loaded: true });
  });
}
