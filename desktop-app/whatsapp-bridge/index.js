// Node child process that speaks the WhatsApp multi-device "linked device" protocol
// (the same one web.whatsapp.com and WhatsApp Desktop use) via Baileys, and bridges it
// to the Rust backend as line-delimited JSON over stdio: one JSON object per line on
// stdout (events), one JSON object per line expected on stdin (commands).
//
// Spawned as: node index.js --session-dir <path> [--standalone]
// --standalone prints the QR to the terminal too, for testing this file on its own
// without the Tauri app.

import {
  makeWASocket,
  useMultiFileAuthState,
  makeCacheableSignalKeyStore,
  downloadMediaMessage,
  DisconnectReason,
} from "@whiskeysockets/baileys";
import pino from "pino";
import readline from "node:readline";
import { mkdirSync } from "node:fs";

const args = parseArgs(process.argv.slice(2));
const sessionDir = args["session-dir"] ?? "./.session";
const standalone = args["standalone"] === true;

mkdirSync(sessionDir, { recursive: true });

const logger = pino({ level: "silent" });

// Recent messages, keyed by WhatsApp message id, so `fetch_media` can look up the
// original WAMessage object Baileys needs to download media on demand. Capped so a
// long-running session doesn't grow unbounded.
const MESSAGE_CACHE_LIMIT = 2000;
const messageCache = new Map();

function cacheMessage(msg) {
  const id = msg.key?.id;
  if (!id) return;
  messageCache.set(id, msg);
  if (messageCache.size > MESSAGE_CACHE_LIMIT) {
    const oldest = messageCache.keys().next().value;
    messageCache.delete(oldest);
  }
}

function send(event) {
  process.stdout.write(JSON.stringify(event) + "\n");
}

function sendAck(id, ok, extra = {}) {
  send({ type: "ack", id, ok, ...extra });
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith("--")) {
      const key = argv[i].slice(2);
      const next = argv[i + 1];
      if (next && !next.startsWith("--")) {
        out[key] = next;
        i++;
      } else {
        out[key] = true;
      }
    }
  }
  return out;
}

function isGroupJid(jid) {
  return typeof jid === "string" && jid.endsWith("@g.us");
}

function messageBodyType(m) {
  if (!m) return null;
  const keys = Object.keys(m);
  return keys.find((k) => k.endsWith("Message") || k === "conversation") ?? null;
}

// Reduce a Baileys WAMessage down to the fields the frontend chat UI needs, without
// embedding raw media bytes — those are fetched lazily via `fetch_media`, mirroring
// the existing Photos thumbnail-then-full-fetch pattern in the desktop app.
function serializeMessage(msg) {
  const content = msg.message ?? {};
  const type = messageBodyType(content);
  const base = {
    id: msg.key?.id,
    chatId: msg.key?.remoteJid,
    fromMe: !!msg.key?.fromMe,
    participant: msg.key?.participant ?? null,
    timestamp: typeof msg.messageTimestamp === "number" ? msg.messageTimestamp : Number(msg.messageTimestamp ?? 0),
    pushName: msg.pushName ?? null,
    type,
  };

  switch (type) {
    case "conversation":
      return { ...base, text: content.conversation };
    case "extendedTextMessage":
      return {
        ...base,
        text: content.extendedTextMessage?.text ?? "",
        quotedId: content.extendedTextMessage?.contextInfo?.stanzaId ?? null,
      };
    case "imageMessage":
      return {
        ...base,
        media: "image",
        mimeType: content.imageMessage?.mimetype,
        caption: content.imageMessage?.caption ?? "",
        thumbnailBase64: content.imageMessage?.jpegThumbnail
          ? Buffer.from(content.imageMessage.jpegThumbnail).toString("base64")
          : null,
      };
    case "videoMessage":
      return {
        ...base,
        media: "video",
        mimeType: content.videoMessage?.mimetype,
        caption: content.videoMessage?.caption ?? "",
        thumbnailBase64: content.videoMessage?.jpegThumbnail
          ? Buffer.from(content.videoMessage.jpegThumbnail).toString("base64")
          : null,
      };
    case "audioMessage":
      return {
        ...base,
        media: "audio",
        mimeType: content.audioMessage?.mimetype,
        isVoiceNote: !!content.audioMessage?.ptt,
        seconds: content.audioMessage?.seconds ?? null,
      };
    case "documentMessage":
      return {
        ...base,
        media: "document",
        mimeType: content.documentMessage?.mimetype,
        fileName: content.documentMessage?.fileName ?? "file",
        fileLength: content.documentMessage?.fileLength ?? null,
      };
    case "reactionMessage":
      return {
        ...base,
        reaction: {
          text: content.reactionMessage?.text ?? "",
          targetId: content.reactionMessage?.key?.id ?? null,
        },
      };
    default:
      return { ...base, text: "", unsupported: true };
  }
}

function serializeChat(chat) {
  return {
    id: chat.id,
    name: chat.name ?? null,
    isGroup: isGroupJid(chat.id),
    unreadCount: chat.unreadCount ?? 0,
    conversationTimestamp:
      typeof chat.conversationTimestamp === "number"
        ? chat.conversationTimestamp
        : Number(chat.conversationTimestamp ?? 0) || null,
  };
}

async function start() {
  const { state, saveCreds } = await useMultiFileAuthState(sessionDir);

  const sock = makeWASocket({
    auth: {
      creds: state.creds,
      keys: makeCacheableSignalKeyStore(state.keys, logger),
    },
    logger,
    syncFullHistory: false,
    generateHighQualityLinkPreview: false,
  });

  sock.ev.on("creds.update", saveCreds);

  sock.ev.on("connection.update", (update) => {
    const { connection, lastDisconnect, qr } = update;

    if (qr) {
      send({ type: "qr", payload: { qr } });
      if (standalone) {
        import("qrcode-terminal").then(({ default: qrcodeTerminal }) => {
          qrcodeTerminal.generate(qr, { small: true });
        });
      }
    }

    if (connection === "connecting") {
      send({ type: "connection", payload: { status: "connecting" } });
    } else if (connection === "open") {
      send({ type: "connection", payload: { status: "open" } });
    } else if (connection === "close") {
      const statusCode = lastDisconnect?.error?.output?.statusCode;
      const loggedOut = statusCode === DisconnectReason.loggedOut;
      send({ type: "connection", payload: { status: "close", loggedOut } });
      if (!loggedOut) {
        start().catch((err) => send({ type: "log", payload: { level: "error", message: String(err) } }));
      }
    }
  });

  sock.ev.on("chats.upsert", (chats) => {
    send({ type: "chats", payload: { chats: chats.map(serializeChat) } });
  });
  sock.ev.on("chats.update", (updates) => {
    send({ type: "chats", payload: { chats: updates.map(serializeChat) } });
  });

  sock.ev.on("contacts.upsert", (contacts) => {
    send({
      type: "contacts",
      payload: {
        contacts: contacts.map((c) => ({ id: c.id, name: c.name ?? c.notify ?? null })),
      },
    });
  });

  sock.ev.on("messaging-history.set", ({ chats, contacts, messages }) => {
    for (const m of messages) cacheMessage(m);
    send({
      type: "history",
      payload: {
        chats: chats.map(serializeChat),
        contacts: contacts.map((c) => ({ id: c.id, name: c.name ?? c.notify ?? null })),
        messages: messages.map(serializeMessage),
      },
    });
  });

  sock.ev.on("messages.upsert", ({ messages, type }) => {
    for (const m of messages) cacheMessage(m);
    send({ type: "messages", payload: { upsertType: type, messages: messages.map(serializeMessage) } });
  });

  sock.ev.on("messages.update", (updates) => {
    send({
      type: "receipt",
      payload: updates.map((u) => ({
        id: u.key?.id,
        chatId: u.key?.remoteJid,
        status: u.update?.status ?? null,
      })),
    });
  });

  sock.ev.on("presence.update", ({ id, presences }) => {
    send({ type: "presence", payload: { chatId: id, presences } });
  });

  return sock;
}

let sock = null;
start()
  .then((s) => {
    sock = s;
    send({ type: "ready" });
  })
  .catch((err) => {
    send({ type: "log", payload: { level: "error", message: String(err?.stack ?? err) } });
    process.exit(1);
  });

// --- stdin command handling ---

const rl = readline.createInterface({ input: process.stdin, terminal: false });

rl.on("line", async (line) => {
  line = line.trim();
  if (!line) return;

  let cmd;
  try {
    cmd = JSON.parse(line);
  } catch {
    return; // ignore malformed input rather than crashing the bridge
  }

  if (!sock) {
    sendAck(cmd.id, false, { error: "not_connected" });
    return;
  }

  try {
    switch (cmd.cmd) {
      case "send_text": {
        const result = await sock.sendMessage(cmd.jid, { text: cmd.text });
        cacheMessage(result);
        sendAck(cmd.id, true, { message: serializeMessage(result) });
        break;
      }
      case "send_media": {
        const buffer = Buffer.from(cmd.dataBase64, "base64");
        const content = buildMediaContent(cmd.mediaType, buffer, cmd);
        const result = await sock.sendMessage(cmd.jid, content);
        cacheMessage(result);
        sendAck(cmd.id, true, { message: serializeMessage(result) });
        break;
      }
      case "fetch_media": {
        const original = messageCache.get(cmd.messageId);
        if (!original) {
          sendAck(cmd.id, false, { error: "message_not_cached" });
          break;
        }
        const buffer = await downloadMediaMessage(original, "buffer", {});
        const type = messageBodyType(original.message);
        const mimeType = original.message?.[type]?.mimetype ?? "application/octet-stream";
        sendAck(cmd.id, true, {
          messageId: cmd.messageId,
          dataBase64: buffer.toString("base64"),
          mimeType,
        });
        break;
      }
      case "mark_read": {
        await sock.readMessages(cmd.messageKeys);
        sendAck(cmd.id, true);
        break;
      }
      case "send_reaction": {
        await sock.sendMessage(cmd.jid, {
          react: { text: cmd.emoji, key: cmd.messageKey },
        });
        sendAck(cmd.id, true);
        break;
      }
      case "logout": {
        await sock.logout();
        sendAck(cmd.id, true);
        break;
      }
      default:
        sendAck(cmd.id, false, { error: "unknown_command" });
    }
  } catch (err) {
    sendAck(cmd.id, false, { error: String(err?.message ?? err) });
  }
});

function buildMediaContent(mediaType, buffer, cmd) {
  switch (mediaType) {
    case "image":
      return { image: buffer, caption: cmd.caption, mimetype: cmd.mimeType };
    case "video":
      return { video: buffer, caption: cmd.caption, mimetype: cmd.mimeType };
    case "audio":
      return { audio: buffer, mimetype: cmd.mimeType ?? "audio/ogg; codecs=opus", ptt: true };
    case "document":
      return { document: buffer, mimetype: cmd.mimeType, fileName: cmd.fileName ?? "file" };
    default:
      throw new Error(`unsupported media type: ${mediaType}`);
  }
}
