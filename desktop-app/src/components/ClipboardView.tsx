import { useEffect, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Laptop, Smartphone, Copy, Check, Trash2, Clipboard as ClipboardIcon } from "lucide-react";
import { SectionHeader } from "./SectionHeader";
import { Placeholder, ConfirmDialog } from "./ContactDetail";
import { sectionMeta } from "../theme/sections";
import { relativeTime } from "../utils/relativeTime";
import {
  initClipboardHistoryListeners,
  copyClipboardEntry,
  clearClipboardHistory,
  useClipboardHistoryStore,
  type ClipboardEntry,
} from "../store/clipboardHistory";

export function ClipboardView() {
  const { entries, loaded } = useClipboardHistoryStore();
  const [confirmingClear, setConfirmingClear] = useState(false);

  useEffect(() => {
    initClipboardHistoryListeners();
  }, []);

  return (
    <div className="flex h-full flex-col">
      <SectionHeader
        section={sectionMeta("clipboard")}
        subtitle={`${entries.length} item${entries.length === 1 ? "" : "s"}`}
        trailing={
          entries.length > 0 && (
            <button
              onClick={() => setConfirmingClear(true)}
              className="flex items-center gap-1 rounded-md px-2.5 py-1 text-xs font-medium text-neutral-500 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
            >
              <Trash2 className="h-3.5 w-3.5" />
              Clear
            </button>
          )
        }
      />

      <div className="flex-1 overflow-y-auto p-4">
        {!loaded ? null : entries.length === 0 ? (
          <Placeholder icon={ClipboardIcon} text="Nothing copied yet — copies on either device will show up here" />
        ) : (
          <ul className="space-y-2">
            <AnimatePresence initial={false}>
              {entries.map((entry) => (
                <ClipboardRow key={entry.id} entry={entry} />
              ))}
            </AnimatePresence>
          </ul>
        )}
      </div>

      {confirmingClear && (
        <ConfirmDialog
          title="Clear clipboard history?"
          message="This can't be undone."
          onCancel={() => setConfirmingClear(false)}
          onConfirm={async () => {
            await clearClipboardHistory();
            setConfirmingClear(false);
          }}
        />
      )}
    </div>
  );
}

function ClipboardRow({ entry }: { entry: ClipboardEntry }) {
  const [copied, setCopied] = useState(false);
  const SourceIcon = entry.source === "mac" ? Laptop : Smartphone;

  return (
    <motion.li
      layout
      initial={{ opacity: 0, y: -8, height: 0 }}
      animate={{ opacity: 1, y: 0, height: "auto" }}
      exit={{ opacity: 0, x: 24 }}
      transition={{ duration: 0.2, ease: "easeOut" }}
      className="flex gap-3 rounded-xl border border-black/5 dark:border-white/10 p-3"
    >
      <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-cyan-500/10 text-cyan-600 dark:text-cyan-400">
        <SourceIcon className="h-4 w-4" />
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-neutral-500 dark:text-neutral-400">
            {entry.source === "mac" ? "This Mac" : "Phone"}
          </span>
          <span className="flex-1" />
          <span className="text-xs text-neutral-400 dark:text-neutral-500">{relativeTime(entry.timestamp)}</span>
          <button
            onClick={async () => {
              await copyClipboardEntry(entry.text);
              setCopied(true);
              setTimeout(() => setCopied(false), 1500);
            }}
            title="Copy"
            className="text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-200 transition-colors"
          >
            {copied ? <Check className="h-3.5 w-3.5 text-emerald-500" /> : <Copy className="h-3.5 w-3.5" />}
          </button>
        </div>
        <p className="mt-0.5 whitespace-pre-wrap break-words line-clamp-4 text-[13px] text-neutral-800 dark:text-neutral-200">
          {entry.text}
        </p>
      </div>
    </motion.li>
  );
}
