import { useEffect, useRef, useState } from "react";
import { motion } from "framer-motion";
import { invoke } from "@tauri-apps/api/core";
import { Channel } from "@tauri-apps/api/core";
import { ChevronLeft, Circle, LayoutGrid, MonitorSmartphone } from "lucide-react";
import { SectionHeader } from "./SectionHeader";
import { sectionMeta } from "../theme/sections";
import { initMirrorListeners, useMirrorStore } from "../store/mirror";

const STOPPED_REASON_MESSAGE: Record<string, string> = {
  permission_denied:
    "Screen mirroring permission was denied on your phone. Click Start Mirroring to try again — you'll need to accept the prompt on your phone this time.",
  error: "Screen mirroring stopped unexpectedly. Click Start Mirroring to try again.",
};

const DEFAULT_MESSAGE =
  "Mirror your phone's screen here. Starting this will show a permission prompt on your phone — that's an Android requirement, not skippable.";

export function ScreenMirrorView() {
  const { isActive, config, stoppedReason, loaded } = useMirrorStore();
  const [starting, setStarting] = useState(false);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const channelRef = useRef<Channel<ArrayBuffer> | null>(null);

  useEffect(() => {
    initMirrorListeners();
  }, []);

  useEffect(() => {
    if (isActive) setStarting(false);
  }, [isActive]);

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
      <SectionHeader section={sectionMeta("mirroring")} subtitle={isActive ? "Mirroring" : undefined} />
      {isActive && config ? (
        <ActiveMirror config={config} canvasRef={canvasRef} onStop={handleStop} />
      ) : (
        <div className="flex h-full flex-col items-center justify-center gap-3 px-8 text-center">
          <span className="flex h-16 w-16 items-center justify-center rounded-full bg-teal-500/10 dark:bg-teal-400/10">
            <MonitorSmartphone className="h-7 w-7 text-teal-600 dark:text-teal-400" strokeWidth={1.75} />
          </span>
          <p className="max-w-xs text-sm text-neutral-500 dark:text-neutral-400">
            {stoppedReason ? (STOPPED_REASON_MESSAGE[stoppedReason] ?? DEFAULT_MESSAGE) : DEFAULT_MESSAGE}
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
  onStop: () => void;
}

function ActiveMirror({ config, canvasRef, onStop }: ActiveMirrorProps) {
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
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.2 }}
        className="flex flex-1 items-center justify-center overflow-hidden bg-black"
      >
        <canvas
          ref={canvasRef}
          width={config.width}
          height={config.height}
          onMouseDown={handleMouseDown}
          onMouseUp={handleMouseUp}
          className="max-h-full max-w-full cursor-pointer"
          style={{ aspectRatio: `${config.width} / ${config.height}` }}
        />
      </motion.div>
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

        <button
          onClick={onStop}
          className="rounded-full bg-black/5 dark:bg-white/10 px-3 py-1.5 text-sm font-medium text-neutral-700 dark:text-neutral-200 transition-colors hover:bg-black/10 dark:hover:bg-white/15"
        >
          Stop Mirroring
        </button>
      </div>
    </>
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
