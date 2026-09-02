import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export type WaLinkStatus = "unlinked" | "qr" | "connecting" | "open" | "loggedOut";

export interface WaChat {
  id: string;
  name: string | null;
  isGroup: boolean;
  unreadCount: number;
  conversationTimestamp: number | null;
}

export interface WaReaction {
  text: string;
  targetId: string | null;
}

export interface WaMessage {
  id: string;
  chatId: string;
  fromMe: boolean;
  participant: string | null;
  timestamp: number;
  pushName: string | null;
  type: string | null;
  text?: string;
  quotedId?: string | null;
  media?: "image" | "video" | "audio" | "document";
  mimeType?: string;
  caption?: string;
  thumbnailBase64?: string | null;
  isVoiceNote?: boolean;
  seconds?: number | null;
  fileName?: string;
  fileLength?: number | null;
  reaction?: WaReaction;
  unsupported?: boolean;
}

interface MediaResult {
  dataBase64: string;
  mimeType: string;
}

interface WhatsappState {
  linkStatus: WaLinkStatus;
  qr: string | null;
  chats: WaChat[];
  contactNames: Record<string, string>;
  messagesByChat: Record<string, WaMessage[]>;
  mediaCache: Record<string, MediaResult>;
  /** Raw Baileys `WAMessageStatus` code per message id — 1 pending, 2 sent, 3 delivered,
   *  4 read, 5 played. Matches the Rust side's `WhatsappState::delivery_status` exactly. */
  deliveryStatus: Record<string, number>;
  typingByChat: Record<string, boolean>;
  loaded: boolean;
}

export const useWhatsappStore = create<WhatsappState>(() => ({
  linkStatus: "unlinked",
  qr: null,
  chats: [],
  contactNames: {},
  messagesByChat: {},
  mediaCache: {},
  deliveryStatus: {},
  typingByChat: {},
  loaded: false,
}));

export function displayNameForWaChat(chat: WaChat): string {
  return chat.name ?? useWhatsappStore.getState().contactNames[chat.id] ?? chat.id.split("@")[0];
}

export async function linkWhatsapp() {
  const status = await invoke<{ linkStatus: WaLinkStatus; qr: string | null }>("whatsapp_link_start");
  useWhatsappStore.setState({ linkStatus: status.linkStatus, qr: status.qr });
}

export async function logoutWhatsapp() {
  await invoke("whatsapp_logout");
  useWhatsappStore.setState({
    linkStatus: "unlinked",
    qr: null,
    chats: [],
    contactNames: {},
    messagesByChat: {},
    mediaCache: {},
    deliveryStatus: {},
    typingByChat: {},
  });
}

export async function loadWaMessages(chatId: string) {
  if (useWhatsappStore.getState().messagesByChat[chatId]) return;
  const messages = await invoke<WaMessage[]>("whatsapp_list_messages", { chatId });
  useWhatsappStore.setState((s) => ({ messagesByChat: { ...s.messagesByChat, [chatId]: messages } }));
}

export async function sendWhatsappMessage(jid: string, text: string) {
  await invoke("whatsapp_send_message", { jid, text });
}

export async function sendWhatsappMedia(
  jid: string,
  mediaType: "image" | "video" | "audio" | "document",
  dataBase64: string,
  mimeType: string,
  fileName?: string,
  caption?: string,
) {
  await invoke("whatsapp_send_media", { jid, mediaType, dataBase64, mimeType, fileName, caption });
}

export async function requestWhatsappMedia(messageId: string): Promise<MediaResult> {
  const cached = useWhatsappStore.getState().mediaCache[messageId];
  if (cached) return cached;

  const fromBackendCache = await invoke<[string, string] | null>("whatsapp_get_media", { messageId });
  if (fromBackendCache) {
    const value = { dataBase64: fromBackendCache[0], mimeType: fromBackendCache[1] };
    useWhatsappStore.setState((s) => ({ mediaCache: { ...s.mediaCache, [messageId]: value } }));
    return value;
  }

  const [dataBase64, mimeType] = await invoke<[string, string]>("whatsapp_request_media", { messageId });
  const value = { dataBase64, mimeType };
  useWhatsappStore.setState((s) => ({ mediaCache: { ...s.mediaCache, [messageId]: value } }));
  return value;
}

interface WaMessageKey {
  remoteJid: string;
  id: string;
  fromMe: boolean;
  participant?: string;
}

function keyFor(message: WaMessage): WaMessageKey {
  return {
    remoteJid: message.chatId,
    id: message.id,
    fromMe: message.fromMe,
    ...(message.participant ? { participant: message.participant } : {}),
  };
}

/** Marks every not-from-me message currently loaded for this chat as read, and optimistically
 *  zeroes the chat's unread badge — mirrors the optimistic-echo pattern `sendSms` uses on the
 *  SMS side rather than waiting on a round trip before the badge clears. */
export async function markWhatsappChatRead(chatId: string) {
  const messages = useWhatsappStore.getState().messagesByChat[chatId] ?? [];
  const keys = messages.filter((m) => !m.fromMe).map(keyFor);
  useWhatsappStore.setState((s) => ({
    chats: s.chats.map((c) => (c.id === chatId ? { ...c, unreadCount: 0 } : c)),
  }));
  if (keys.length === 0) return;
  await invoke("whatsapp_mark_read", { chatId, messageKeys: keys });
}

export async function sendWhatsappReaction(message: WaMessage, emoji: string) {
  await invoke("whatsapp_send_reaction", { jid: message.chatId, messageKey: keyFor(message), emoji });
}

function upsertMessages(byChat: Record<string, WaMessage[]>, incoming: WaMessage[]): Record<string, WaMessage[]> {
  const next = { ...byChat };
  for (const message of incoming) {
    const existing = next[message.chatId] ?? [];
    const idx = existing.findIndex((m) => m.id === message.id);
    const updated = idx >= 0 ? [...existing.slice(0, idx), message, ...existing.slice(idx + 1)] : [...existing, message];
    updated.sort((a, b) => a.timestamp - b.timestamp);
    next[message.chatId] = updated;
  }
  return next;
}

let initialized = false;

export function initWhatsappListeners() {
  if (initialized) return;
  initialized = true;

  invoke<{ linkStatus: WaLinkStatus; qr: string | null }>("whatsapp_status").then((status) => {
    useWhatsappStore.setState({ linkStatus: status.linkStatus, qr: status.qr, loaded: true });
  });
  invoke<WaChat[]>("whatsapp_list_chats").then((chats) => {
    useWhatsappStore.setState({ chats });
  });

  listen<string | null>("whatsapp-qr", (event) => {
    useWhatsappStore.setState({ qr: event.payload, linkStatus: "qr" });
  });

  listen<{ status: string; loggedOut?: boolean }>("whatsapp-connection", (event) => {
    const { status, loggedOut } = event.payload;
    useWhatsappStore.setState((s) => ({
      linkStatus:
        status === "open"
          ? "open"
          : status === "connecting"
            ? "connecting"
            : status === "close" && loggedOut
              ? "loggedOut"
              : status === "close"
                ? "connecting"
                : s.linkStatus,
      qr: status === "open" ? null : s.qr,
    }));
  });

  listen<WaChat[]>("whatsapp-chats-updated", (event) => {
    useWhatsappStore.setState({ chats: event.payload, loaded: true });
  });

  listen<Record<string, string>>("whatsapp-contacts-updated", (event) => {
    useWhatsappStore.setState({ contactNames: event.payload });
  });

  listen<WaMessage[]>("whatsapp-message", (event) => {
    useWhatsappStore.setState((s) => ({ messagesByChat: upsertMessages(s.messagesByChat, event.payload) }));
  });

  listen<{ id: string; chatId: string; status: number | null }[]>("whatsapp-receipt", (event) => {
    useWhatsappStore.setState((s) => {
      const deliveryStatus = { ...s.deliveryStatus };
      for (const update of event.payload) {
        if (update.status != null) deliveryStatus[update.id] = update.status;
      }
      return { deliveryStatus };
    });
  });

  listen<{ chatId: string; presences: Record<string, { lastKnownPresence?: string }> }>("whatsapp-typing", (event) => {
    const isTyping = Object.values(event.payload.presences ?? {}).some(
      (p) => p.lastKnownPresence === "composing",
    );
    useWhatsappStore.setState((s) => ({
      typingByChat: { ...s.typingByChat, [event.payload.chatId]: isTyping },
    }));
  });
}
