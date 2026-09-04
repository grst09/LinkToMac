import { useEffect, useMemo, useRef, useState } from "react";
import { motion } from "framer-motion";
import { invoke } from "@tauri-apps/api/core";
import { Channel } from "@tauri-apps/api/core";
import { ChevronLeft, Circle, LayoutGrid, MonitorSmartphone, Search } from "lucide-react";
import { SectionHeader } from "./SectionHeader";
import { sectionMeta } from "../theme/sections";
import { initMirrorListeners, launchMirrorApp, useMirrorStore, type AppInfo } from "../store/mirror";

const STOPPED_REASON_MESSAGE: Record<string, string> = {
  permission_denied:
    "Screen mirroring permission was denied on your phone. Click Start Mirroring to try again — you'll need to accept the prompt on your phone this time.",
  error: "Screen mirroring stopped unexpectedly. Click Start Mirroring to try again.",
};

const DEFAULT_MESSAGE =
  "Mirror your phone's screen here. Starting this will show a permission prompt on your phone — that's an Android requirement, not skippable.";

const START_TIMEOUT_MS = 8000;

export function ScreenMirrorView() {
  const { isActive, config, stoppedReason, loaded } = useMirrorStore();
  const [starting, setStarting] = useState(false);
  const [startTimedOut, setStartTimedOut] = useState(false);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const channelRef = useRef<Channel<ArrayBuffer> | null>(null);

  useEffect(() => {
    initMirrorListeners();
  }, []);

  useEffect(() => {
    if (isActive) setStarting(false);
  }, [isActive]);

  // The phone brings its own Activity to the foreground for the MediaProjection permission
  // prompt (see SyncForegroundService.kt's `onMirrorStartRequested` comment) — but Android
  // silently blocks that if the phone's screen is off/locked at the moment the request arrives,
  // with no error reported back at all. Without this, a click just leaves the button stuck on
  // "Starting…" forever with no indication anything went wrong.
  useEffect(() => {
    if (!starting) return;
    setStartTimedOut(false);
    const timer = setTimeout(() => {
      setStarting(false);
      setStartTimedOut(true);
    }, START_TIMEOUT_MS);
    return () => clearTimeout(timer);
  }, [starting]);

  // Tear down the frame channel when the view unmounts or mirroring stops from under us
  // (phone-initiated `mirror.stopped`) — matches `MirrorStore.frameSink` being cleared whenever
  // `ScreenMirrorView` isn't visible in the old Swift app.
  useEffect(() => {
    if (!isActive) channelRef.current = null;
  }, [isActive]);

  useEffect(() => {
    return () => {
      channelRef.current = null;
    };
  }, []);

  async function handleStart() {
    setStarting(true);
    setStartTimedOut(false);
    const channel = new Channel<ArrayBuffer>();
    channel.onmessage = (data) => {
      const canvas = canvasRef.current;
      if (!canvas) return;
      const ctx = canvas.getContext("2d");
      if (!ctx) return;
      // Each frame is self-describing — an 8-byte [width, height] (u32 LE) header followed by
      // RGBA bytes — since the backend downscales for IPC efficiency, so the actual pixel
      // dimensions won't match `mirror.config`'s native-resolution values. Resizing the canvas
      // to match keeps this correct regardless of what the backend chooses to send; it's also
      // what avoids the earlier stale-closure bug where dimensions were captured once at
      // `handleStart` time (before `mirror.config` had even arrived) and never revisited.
      const view = new DataView(data);
      const width = view.getUint32(0, true);
      const height = view.getUint32(4, true);
      const pixels = new Uint8ClampedArray(data, 8);
      if (pixels.length !== width * height * 4) return;
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }
      ctx.putImageData(new ImageData(pixels, width, height), 0, 0);
    };
    channelRef.current = channel;
    await invoke("start_mirroring", { onFrame: channel });
  }

  async function handleStop() {
    channelRef.current = null;
    await invoke("stop_mirroring");
  }

  if (!loaded) return null;

  return (
    <div className="flex h-full flex-col">
      <SectionHeader
        section={sectionMeta("mirroring")}
        subtitle={isActive ? "Mirroring" : undefined}
        trailing={
          isActive ? (
            <button
              onClick={handleStop}
              className="flex items-center gap-1.5 rounded-full bg-red-500 px-3.5 py-1.5 text-sm font-medium text-white shadow-soft transition-opacity hover:opacity-90"
            >
              <span className="h-2 w-2 rounded-sm bg-white" />
              Stop Mirror
            </button>
          ) : undefined
        }
      />
      {isActive && config ? (
        <ActiveMirror config={config} canvasRef={canvasRef} />
      ) : (
        <div className="flex h-full flex-col items-center justify-center gap-3 px-8 text-center">
          <span className="flex h-16 w-16 items-center justify-center rounded-full bg-teal-500/10 dark:bg-teal-400/10">
            <MonitorSmartphone className="h-7 w-7 text-teal-600 dark:text-teal-400" strokeWidth={1.75} />
          </span>
          <p className="max-w-xs text-sm text-neutral-500 dark:text-neutral-400">
            {startTimedOut
              ? "Nothing happened on your phone — this usually means its screen was off or locked when the permission prompt tried to appear. Wake and unlock your phone, then try again."
              : stoppedReason
                ? (STOPPED_REASON_MESSAGE[stoppedReason] ?? DEFAULT_MESSAGE)
                : DEFAULT_MESSAGE}
          </p>
          <motion.button
            whileHover={{ y: -1 }}
            whileTap={{ scale: 0.97 }}
            onClick={handleStart}
            disabled={starting}
            className="rounded-full bg-teal-500 px-4 py-1.5 text-sm font-medium text-white shadow-soft transition-opacity hover:opacity-90 disabled:opacity-50"
          >
            {starting ? "Starting…" : "Start Mirroring"}
          </motion.button>
        </div>
      )}
    </div>
  );
}

interface ActiveMirrorProps {
  config: { width: number; height: number };
  canvasRef: React.RefObject<HTMLCanvasElement | null>;
}

function ActiveMirror({ config, canvasRef }: ActiveMirrorProps) {
  const [textInput, setTextInput] = useState("");
  const dragStart = useRef<{ x: number; y: number } | null>(null);

  function normalize(clientX: number, clientY: number): { x: number; y: number } {
    const canvas = canvasRef.current;
    if (!canvas) return { x: 0, y: 0 };
    const rect = canvas.getBoundingClientRect();
    const x = Math.min(Math.max((clientX - rect.left) / Math.max(rect.width, 1), 0), 1);
    const y = Math.min(Math.max((clientY - rect.top) / Math.max(rect.height, 1), 0), 1);
    return { x, y };
  }

  function handleMouseDown(e: React.MouseEvent<HTMLCanvasElement>) {
    dragStart.current = { x: e.clientX, y: e.clientY };
  }

  async function handleMouseUp(e: React.MouseEvent<HTMLCanvasElement>) {
    const start = dragStart.current;
    dragStart.current = null;
    if (!start) return;
    const distance = Math.hypot(e.clientX - start.x, e.clientY - start.y);
    if (distance < 4) {
      const n = normalize(start.x, start.y);
      await invoke("send_mirror_tap", { x: n.x, y: n.y });
    } else {
      const n1 = normalize(start.x, start.y);
      const n2 = normalize(e.clientX, e.clientY);
      await invoke("send_mirror_swipe", {
        startX: n1.x,
        startY: n1.y,
        endX: n2.x,
        endY: n2.y,
        durationMs: 300,
      });
    }
  }

  function submitText() {
    if (!textInput) return;
    invoke("send_mirror_text", { text: textInput });
    setTextInput("");
  }

  return (
    <>
      <div className="flex flex-1 overflow-hidden">
        <AppsPanel />

        <div className="flex flex-1 items-center justify-center overflow-hidden bg-neutral-100 dark:bg-neutral-950 p-6">
          {/* Phone bezel — a fixed dark frame around the mirrored canvas, matching the
             reference layout's "phone in hand" framing rather than a bare video rect. No
             notch/punch-hole shape drawn on top: Android camera cutouts vary a lot (punch-hole,
             none at all on foldables, different positions) and the mirrored frame itself
             already shows whatever the real phone has — faking one here would just be wrong
             for most devices. */}
          <div className="rounded-[2.5rem] border-[10px] border-neutral-900 bg-neutral-900 shadow-soft-hover">
            <div className="relative overflow-hidden rounded-[1.75rem] bg-black">
              <canvas
                ref={canvasRef}
                width={config.width}
                height={config.height}
                onMouseDown={handleMouseDown}
                onMouseUp={handleMouseUp}
                className="block max-h-[65vh] max-w-[70vw] cursor-pointer"
                style={{ aspectRatio: `${config.width} / ${config.height}` }}
              />
            </div>
          </div>
        </div>
      </div>
      <div className="flex items-center gap-3 border-t border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900 p-2 shadow-soft">
        <IconButton title="Back" onClick={() => invoke("send_mirror_key", { action: "back" })}>
          <ChevronLeft className="h-4 w-4" />
        </IconButton>
        <IconButton title="Home" onClick={() => invoke("send_mirror_key", { action: "home" })}>
          <Circle className="h-4 w-4" />
        </IconButton>
        <IconButton title="Recents" onClick={() => invoke("send_mirror_key", { action: "recents" })}>
          <LayoutGrid className="h-4 w-4" />
        </IconButton>

        <span className="flex-1" />

        <input
          type="text"
          value={textInput}
          onChange={(e) => setTextInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") submitText();
          }}
          placeholder="Type to send…"
          className="w-56 rounded-lg border border-black/10 dark:border-white/15 bg-transparent px-2.5 py-1 text-sm text-neutral-900 dark:text-neutral-100 outline-none focus:border-teal-500"
        />
      </div>
    </>
  );
}

function AppsPanel() {
  const apps = useMirrorStore((s) => s.apps);
  const [query, setQuery] = useState("");

  const filtered = useMemo(() => {
    if (!query.trim()) return apps;
    const q = query.toLowerCase();
    return apps.filter((a) => a.appName.toLowerCase().includes(q));
  }, [apps, query]);

  return (
    <div className="flex w-72 shrink-0 flex-col border-r border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900">
      <div className="flex items-center justify-between px-3.5 pb-2 pt-3.5">
        <h2 className="text-sm font-semibold text-neutral-900 dark:text-neutral-100">Apps</h2>
        <span className="rounded-full bg-black/[0.04] dark:bg-white/[0.08] px-2 py-0.5 text-xs font-medium text-neutral-500 dark:text-neutral-400">
          {apps.length}
        </span>
      </div>
      <div className="relative px-3.5 pb-3">
        <Search className="pointer-events-none absolute left-6 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-neutral-400" />
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search apps…"
          className="w-full rounded-lg bg-black/[0.04] dark:bg-white/[0.06] py-1.5 pl-8 pr-3 text-[13px] text-neutral-900 dark:text-neutral-100 placeholder:text-neutral-400 focus:outline-none focus:ring-2 focus:ring-teal-500/40"
        />
      </div>
      <div className="flex-1 overflow-y-auto px-2 pb-3">
        {apps.length === 0 ? (
          <p className="px-2 py-6 text-center text-xs text-neutral-400 dark:text-neutral-500">
            Loading apps…
          </p>
        ) : filtered.length === 0 ? (
          <p className="px-2 py-6 text-center text-xs text-neutral-400 dark:text-neutral-500">
            No matching apps
          </p>
        ) : (
          <div className="grid grid-cols-3 gap-1.5">
            {filtered.map((app) => (
              <AppTile key={app.packageName} app={app} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function AppTile({ app }: { app: AppInfo }) {
  return (
    <button
      onClick={() => launchMirrorApp(app.packageName)}
      title={app.appName}
      className="flex flex-col items-center gap-1 rounded-xl p-2 text-center transition-colors hover:bg-black/[0.04] dark:hover:bg-white/[0.06]"
    >
      <img
        src={`data:image/png;base64,${app.iconBase64}`}
        alt=""
        className="h-10 w-10 rounded-xl shadow-soft"
      />
      <span className="line-clamp-2 text-[11px] leading-tight text-neutral-700 dark:text-neutral-300">
        {app.appName}
      </span>
    </button>
  );
}

function IconButton({
  title,
  onClick,
  children,
}: {
  title: string;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      title={title}
      onClick={onClick}
      className="flex h-8 w-8 items-center justify-center rounded-full text-neutral-600 dark:text-neutral-300 transition-colors hover:bg-black/5 dark:hover:bg-white/10"
    >
      {children}
    </button>
  );
}
