import { useEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import QRCode from "qrcode";
import {
  Check,
  CheckCheck,
  Clock,
  FileText,
  MessageCircleOff,
  Paperclip,
  Play,
  Send,
  Smile,
  Users,
  X,
} from "lucide-react";
import { InitialsAvatar } from "./InitialsAvatar";
import {
  displayNameForWaChat,
  linkWhatsapp,
  loadWaMessages,
  markWhatsappChatRead,
  requestWhatsappMedia,
  sendWhatsappMedia,
  sendWhatsappMessage,
  sendWhatsappReaction,
  useWhatsappStore,
  type WaChat,
  type WaMessage,
} from "../store/whatsapp";
import { groupIntoBursts } from "../utils/messageGrouping";
import { formatClockTime } from "../utils/relativeTime";

const QUICK_REACTIONS = ["👍", "❤️", "😂", "😮", "😢", "🙏"];

/** Shown in the list column while unlinked/linking — the same "show QR → wait for scan → show
 *  connected" shape as `ThisDeviceView`'s phone-pairing flow, just driven by Baileys' own QR
 *  string instead of this app's own pairing payload. */
export function WhatsAppLinkCard() {
  // Two separate primitive selectors, not one selector returning `{ linkStatus, qr }` — a
  // selector that allocates a new object every call defeats Zustand's reference-equality
  // check and causes an infinite render loop (confirmed via the browser console while
  // testing this component: "Maximum update depth exceeded").
  const linkStatus = useWhatsappStore((s) => s.linkStatus);
  const qr = useWhatsappStore((s) => s.qr);
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);

  useEffect(() => {
    linkWhatsapp();
  }, []);

  useEffect(() => {
    if (!qr) {
      setQrDataUrl(null);
      return;
    }
    QRCode.toDataURL(qr, { width: 220, margin: 1 }).then(setQrDataUrl);
  }, [qr]);

  return (
    <div className="flex h-full flex-col items-center justify-center gap-4 p-6 text-center">
      {linkStatus === "qr" && qrDataUrl ? (
        <>
          <img src={qrDataUrl} alt="WhatsApp linking QR code" className="rounded-lg border border-black/10 dark:border-white/10" />
          <div>
            <p className="font-medium text-neutral-700 dark:text-neutral-200">Scan with WhatsApp</p>
            <p className="mt-1 max-w-[220px] text-xs text-neutral-500 dark:text-neutral-400">
              On your phone: WhatsApp → Settings → Linked Devices → Link a Device
            </p>
          </div>
        </>
      ) : (
        <>
          <div className="flex h-14 w-14 items-center justify-center rounded-full bg-green-500/10">
            <MessageCircleOff className="h-7 w-7 text-green-600 dark:text-green-400" />
          </div>
          <div>
            <p className="font-medium text-neutral-700 dark:text-neutral-200">
              {linkStatus === "connecting" ? "Connecting…" : linkStatus === "loggedOut" ? "Logged out" : "Not linked"}
            </p>
            <p className="mt-1 max-w-[220px] text-xs text-neutral-500 dark:text-neutral-400">
              {linkStatus === "connecting"
                ? "Waiting for a QR code from WhatsApp."
                : "Link your WhatsApp account to see chats here."}
            </p>
          </div>
        </>
      )}
    </div>
  );
}

export function WhatsAppChatList({
  selectedChatId,
  onSelect,
}: {
  selectedChatId: string | null;
  onSelect: (chatId: string) => void;
}) {
  const chats = useWhatsappStore((s) => s.chats);
  const messagesByChat = useWhatsappStore((s) => s.messagesByChat);

  if (chats.length === 0) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-2 text-neutral-400 dark:text-neutral-500">
        <Users className="h-8 w-8" />
        <p className="text-sm">No chats yet</p>
      </div>
    );
  }

  return (
    <ul className="space-y-0.5 py-1">
      {chats.map((chat) => {
        const name = displayNameForWaChat(chat);
        const last = messagesByChat[chat.id]?.[messagesByChat[chat.id].length - 1];
        return (
          <li key={chat.id} className="px-2">
            <button
              onClick={() => onSelect(chat.id)}
              className={`flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2.5 text-left transition-colors ${
                chat.id === selectedChatId ? "bg-green-500/10 dark:bg-green-400/10 shadow-soft" : "hover:bg-black/[0.03] dark:hover:bg-white/[0.05]"
              }`}
            >
              <InitialsAvatar name={name} diameter={38} />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5">
                  <span className="truncate text-[13px] font-semibold text-neutral-900 dark:text-neutral-100">
                    {name}
                  </span>
                  {chat.isGroup && <Users className="h-3 w-3 shrink-0 text-neutral-400" />}
                </div>
                <p className="truncate text-xs text-neutral-500 dark:text-neutral-400">
                  {last ? bubblePreview(last) : " "}
                </p>
              </div>
              {chat.unreadCount > 0 && (
                <span className="flex h-5 min-w-[20px] items-center justify-center rounded-full bg-green-500 px-1.5 text-[11px] font-semibold text-white">
                  {chat.unreadCount}
                </span>
              )}
            </button>
          </li>
        );
      })}
    </ul>
  );
}

function bubblePreview(m: WaMessage): string {
  if (m.reaction) return `Reacted ${m.reaction.text}`;
  if (m.media === "image") return m.caption || "📷 Photo";
  if (m.media === "video") return m.caption || "🎥 Video";
  if (m.media === "audio") return m.isVoiceNote ? "🎤 Voice message" : "🎵 Audio";
  if (m.media === "document") return `📄 ${m.fileName ?? "Document"}`;
  return m.text ?? "";
}

export function WhatsAppConversation({ chat }: { chat: WaChat }) {
  const name = displayNameForWaChat(chat);
  const messages = useWhatsappStore((s) => s.messagesByChat[chat.id] ?? []);
  const isTyping = useWhatsappStore((s) => s.typingByChat[chat.id] ?? false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    loadWaMessages(chat.id);
  }, [chat.id]);

  useEffect(() => {
    if (chat.unreadCount > 0) markWhatsappChatRead(chat.id);
  }, [chat.id, chat.unreadCount]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight });
  }, [messages.length]);

  async function handleFilePicked(file: File) {
    const buffer = await file.arrayBuffer();
    const dataBase64 = arrayBufferToBase64(buffer);
    const mediaType = mediaTypeForMime(file.type);
    await sendWhatsappMedia(chat.id, mediaType, dataBase64, file.type || "application/octet-stream", file.name);
  }

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-2.5 px-3.5 py-2.5">
        <InitialsAvatar name={name} diameter={30} />
        <div className="min-w-0 flex-1">
          <div className="font-semibold text-neutral-900 dark:text-neutral-100">{name}</div>
          {isTyping && <div className="text-xs text-green-600 dark:text-green-400">typing…</div>}
        </div>
      </div>
      <div className="border-t border-black/5 dark:border-white/10" />
      <div ref={scrollRef} className="flex-1 space-y-3 overflow-y-auto p-3">
        {groupIntoBursts(messages, (a, b) => a.fromMe === b.fromMe && a.participant === b.participant).map(
          (burst) => {
            const outgoing = burst[0].fromMe;
            const senderLabel = outgoing ? "You" : (burst[0].pushName ?? displayNameForWaChat(chat));
            return (
              <div key={burst[0].id} className={`flex flex-col ${outgoing ? "items-end" : "items-start"}`}>
                {(chat.isGroup || outgoing) && (
                  <span className="mb-1 px-1 text-[11px] font-medium text-neutral-400 dark:text-neutral-500">
                    {senderLabel}
                  </span>
                )}
                <div className="flex flex-col gap-1">
                  {burst.map((m, i) => (
                    <MessageBubble key={m.id} message={m} isLastInBurst={i === burst.length - 1} />
                  ))}
                </div>
              </div>
            );
          },
        )}
      </div>
      <div className="flex items-end gap-2 border-t border-black/5 dark:border-white/10 p-3">
        <input
          ref={fileInputRef}
          type="file"
          className="hidden"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) handleFilePicked(file);
            e.target.value = "";
          }}
        />
        <button
          onClick={() => fileInputRef.current?.click()}
          title="Attach file"
          className="rounded-lg p-2 text-neutral-500 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
        >
          <Paperclip className="h-4 w-4" />
        </button>
        <WaMessageInput onSend={(text) => sendWhatsappMessage(chat.id, text)} />
      </div>
    </div>
  );
}

function WaMessageInput({ onSend }: { onSend: (text: string) => void }) {
  const [draft, setDraft] = useState("");
  const trimmed = draft.trim();

  function send() {
    if (!trimmed) return;
    onSend(trimmed);
    setDraft("");
  }

  return (
    <>
      <textarea
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            send();
          }
        }}
        placeholder="Message"
        rows={1}
        className="flex-1 resize-none rounded-lg border border-black/10 dark:border-white/15 bg-transparent px-3 py-2 text-[13px] focus:outline-none focus:ring-2 focus:ring-green-500/40"
      />
      <button
        onClick={send}
        disabled={!trimmed}
        className="rounded-lg bg-green-500 p-2 text-white disabled:opacity-40 hover:bg-green-600 transition-colors"
      >
        <Send className="h-4 w-4" />
      </button>
    </>
  );
}

function MessageBubble({ message, isLastInBurst }: { message: WaMessage; isLastInBurst: boolean }) {
  const deliveryStatus = useWhatsappStore((s) => s.deliveryStatus[message.id]);
  const [showReactions, setShowReactions] = useState(false);
  const outgoing = message.fromMe;

  if (message.reaction) return null; // reactions render as a badge on the target bubble, not their own row

  return (
    <motion.div
      initial={{ opacity: 0, y: 4 }}
      animate={{ opacity: 1, y: 0 }}
      className={`group flex ${outgoing ? "justify-end" : "justify-start"}`}
    >
      <div className="relative max-w-[340px]">
        <div
          onDoubleClick={() => setShowReactions(true)}
          className={`rounded-2xl px-3.5 py-2 text-[13px] shadow-soft ${
            outgoing
              ? "bg-green-500 text-white"
              : "border border-black/5 bg-white text-neutral-900 dark:border-white/10 dark:bg-neutral-800 dark:text-neutral-100"
          }`}
        >
          <MessageContent message={message} outgoing={outgoing} />
        </div>
        <div
          className={`mt-1 flex items-center gap-1 px-1 text-[10px] text-neutral-400 dark:text-neutral-500 ${
            outgoing ? "justify-end" : "justify-start"
          } ${isLastInBurst ? "" : "invisible group-hover:visible"}`}
        >
          <span>{isLastInBurst ? formatClockTime(message.timestamp * 1000) : ""}</span>
          {outgoing && isLastInBurst && <DeliveryTicks status={deliveryStatus} />}
          <button
            onClick={() => setShowReactions((v) => !v)}
            className="opacity-0 transition-opacity group-hover:opacity-100"
            title="React"
          >
            <Smile className="h-3 w-3" />
          </button>
        </div>
        {showReactions && (
          <div
            className={`absolute z-10 flex gap-1 rounded-full border border-black/10 bg-white p-1 shadow-lg dark:border-white/10 dark:bg-neutral-800 ${
              outgoing ? "right-0" : "left-0"
            } -top-9`}
          >
            {QUICK_REACTIONS.map((emoji) => (
              <button
                key={emoji}
                onClick={() => {
                  sendWhatsappReaction(message, emoji);
                  setShowReactions(false);
                }}
                className="rounded-full px-1 text-base hover:bg-black/5 dark:hover:bg-white/10"
              >
                {emoji}
              </button>
            ))}
          </div>
        )}
      </div>
    </motion.div>
  );
}

function DeliveryTicks({ status }: { status: number | undefined }) {
  if (status == null || status <= 1) return <Clock className="h-3 w-3" />;
  if (status === 2) return <Check className="h-3 w-3" />;
  const read = status >= 4;
  return <CheckCheck className={`h-3 w-3 ${read ? "text-sky-400" : ""}`} />;
}

function MessageContent({ message, outgoing }: { message: WaMessage; outgoing: boolean }) {
  const [lightboxOpen, setLightboxOpen] = useState(false);

  if (message.media === "image") {
    return (
      <div>
        {message.thumbnailBase64 && (
          <img
            src={`data:image/jpeg;base64,${message.thumbnailBase64}`}
            onClick={() => setLightboxOpen(true)}
            className="max-h-56 max-w-full cursor-pointer rounded-lg"
          />
        )}
        {message.caption && <p className="mt-1">{message.caption}</p>}
        {lightboxOpen && <MediaLightbox message={message} onClose={() => setLightboxOpen(false)} />}
      </div>
    );
  }

  if (message.media === "video") {
    return (
      <div>
        {message.thumbnailBase64 && (
          <div className="relative cursor-pointer" onClick={() => setLightboxOpen(true)}>
            <img src={`data:image/jpeg;base64,${message.thumbnailBase64}`} className="max-h-56 max-w-full rounded-lg" />
            <div className="absolute inset-0 flex items-center justify-center">
              <div className="rounded-full bg-black/50 p-2">
                <Play className="h-5 w-5 fill-white text-white" />
              </div>
            </div>
          </div>
        )}
        {message.caption && <p className="mt-1">{message.caption}</p>}
        {lightboxOpen && <MediaLightbox message={message} onClose={() => setLightboxOpen(false)} />}
      </div>
    );
  }

  if (message.media === "audio") {
    return <VoiceNotePlayer message={message} outgoing={outgoing} />;
  }

  if (message.media === "document") {
    return <DocumentBubble message={message} />;
  }

  return <span className="whitespace-pre-wrap">{message.text}</span>;
}

function MediaLightbox({ message, onClose }: { message: WaMessage; onClose: () => void }) {
  const [src, setSrc] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    requestWhatsappMedia(message.id).then(({ dataBase64, mimeType }) => {
      if (!cancelled) setSrc(`data:${mimeType};base64,${dataBase64}`);
    });
    return () => {
      cancelled = true;
    };
  }, [message.id]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  return (
    <AnimatePresence>
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        onClick={onClose}
        className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-8"
      >
        <button onClick={onClose} className="absolute right-6 top-6 rounded-full bg-white/10 p-2 text-white hover:bg-white/20">
          <X className="h-5 w-5" />
        </button>
        {!src ? (
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/30 border-t-white" />
        ) : message.media === "video" ? (
          <video src={src} controls autoPlay className="max-h-full max-w-full rounded-lg" onClick={(e) => e.stopPropagation()} />
        ) : (
          <img src={src} className="max-h-full max-w-full rounded-lg" onClick={(e) => e.stopPropagation()} />
        )}
      </motion.div>
    </AnimatePresence>
  );
}

function VoiceNotePlayer({ message, outgoing }: { message: WaMessage; outgoing: boolean }) {
  const [src, setSrc] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function play() {
    if (!src) {
      setLoading(true);
      const { dataBase64, mimeType } = await requestWhatsappMedia(message.id);
      setSrc(`data:${mimeType};base64,${dataBase64}`);
      setLoading(false);
    }
  }

  return (
    <div className="flex min-w-[180px] items-center gap-2">
      {src ? (
        <audio src={src} controls autoPlay className="h-8 max-w-[220px]" />
      ) : (
        <button
          onClick={play}
          disabled={loading}
          className={`flex items-center gap-2 rounded-full px-2 py-1 ${outgoing ? "bg-white/20" : "bg-black/10 dark:bg-white/10"}`}
        >
          <Play className="h-3.5 w-3.5" />
          <span className="text-xs">{loading ? "Loading…" : message.isVoiceNote ? "Voice message" : "Play audio"}</span>
        </button>
      )}
      {message.seconds != null && <span className="text-xs opacity-70">{formatDuration(message.seconds)}</span>}
    </div>
  );
}

function DocumentBubble({ message }: { message: WaMessage }) {
  const [downloading, setDownloading] = useState(false);

  async function download() {
    setDownloading(true);
    try {
      await requestWhatsappMedia(message.id);
    } finally {
      setDownloading(false);
    }
  }

  return (
    <button onClick={download} disabled={downloading} className="flex items-center gap-2 text-left">
      <FileText className="h-6 w-6 shrink-0" />
      <div className="min-w-0">
        <div className="truncate font-medium">{message.fileName ?? "Document"}</div>
        <div className="text-xs opacity-70">
          {downloading ? "Downloading…" : message.fileLength ? formatBytes(message.fileLength) : "Tap to download"}
        </div>
      </div>
    </button>
  );
}

function mediaTypeForMime(mime: string): "image" | "video" | "audio" | "document" {
  if (mime.startsWith("image/")) return "image";
  if (mime.startsWith("video/")) return "video";
  if (mime.startsWith("audio/")) return "audio";
  return "document";
}

function arrayBufferToBase64(buffer: ArrayBuffer): string {
  let binary = "";
  const bytes = new Uint8Array(buffer);
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}


function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60)
    .toString()
    .padStart(2, "0");
  return `${m}:${s}`;
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
