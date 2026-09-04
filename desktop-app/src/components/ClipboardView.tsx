import { useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence } from "framer-motion";
import {
  Laptop,
  Smartphone,
  Copy,
  Check,
  Trash2,
  Clipboard as ClipboardIcon,
  Image as ImageIcon,
  Search as SearchIcon,
  Star,
} from "lucide-react";
import { SectionHeader } from "./SectionHeader";
import { SearchBar } from "./SearchBar";
import { AnimatedListRow } from "./AnimatedListRow";
import { Placeholder, ConfirmDialog } from "./ContactDetail";
import { sectionMeta } from "../theme/sections";
import { relativeTime } from "../utils/relativeTime";
import {
  initClipboardHistoryListeners,
  copyClipboardEntry,
  copyClipboardImageEntry,
  clearClipboardHistory,
  setClipboardEntryPinned,
  useClipboardHistoryStore,
  type ClipboardEntry,
} from "../store/clipboardHistory";

const URL_RE = /^[a-z][a-z0-9+.-]*:\/\/\S+$/i;

function entryTypeLabel(text: string): "URL" | "TXT" {
  return URL_RE.test(text.trim()) ? "URL" : "TXT";
}

export function ClipboardView() {
  const { entries, loaded } = useClipboardHistoryStore();
  const [confirmingClear, setConfirmingClear] = useState(false);
  const [searchText, setSearchText] = useState("");
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [justCopiedId, setJustCopiedId] = useState<string | null>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    initClipboardHistoryListeners();
  }, []);

  // Entries arrive pinned-first from the backend already (see `clipboard::ordered` on the Rust
  // side) — filtering by search text preserves that order, so no re-sort needed here.
  const filtered = useMemo(() => {
    const q = searchText.trim().toLowerCase();
    return q ? entries.filter((e) => e.text.toLowerCase().includes(q)) : entries;
  }, [entries, searchText]);

  const pinnedItems = useMemo(() => filtered.filter((e) => e.isPinned), [filtered]);
  const recentItems = useMemo(() => filtered.filter((e) => !e.isPinned), [filtered]);

  // Keeps the keyboard-selected row in range as the list changes underneath it (a search
  // narrowing the results, an item getting pinned and jumping to the top, etc.).
  useEffect(() => {
    setSelectedIndex((i) => Math.min(i, Math.max(filtered.length - 1, 0)));
  }, [filtered.length]);

  async function copyEntry(entry: ClipboardEntry | undefined) {
    if (!entry) return;
    if (entry.imageBase64) {
      await copyClipboardImageEntry(entry.imageBase64);
    } else {
      await copyClipboardEntry(entry.text);
    }
    setJustCopiedId(entry.id);
    setTimeout((id) => setJustCopiedId((cur) => (cur === id ? null : cur)), 1500, entry.id);
  }

  function onSearchKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setSelectedIndex((i) => Math.min(i + 1, filtered.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setSelectedIndex((i) => Math.max(i - 1, 0));
    } else if (e.key === "Enter") {
      e.preventDefault();
      copyEntry(filtered[selectedIndex]);
    }
  }

  // ⌘1–⌘9 jump straight to and copy the Nth visible entry, regardless of where focus is —
  // matches the always-available quick-copy shortcuts a spotlight-style clipboard manager has,
  // even though this is an embedded view rather than a floating overlay.
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (!e.metaKey || !/^[1-9]$/.test(e.key)) return;
      const index = Number(e.key) - 1;
      if (index >= filtered.length) return;
      e.preventDefault();
      setSelectedIndex(index);
      copyEntry(filtered[index]);
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filtered]);

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

      {entries.length > 0 && (
        <SearchBar
          value={searchText}
          onChange={setSearchText}
          onKeyDown={onSearchKeyDown}
          inputRef={searchInputRef}
          placeholder="Search clipboard…"
        />
      )}

      <div className="flex-1 overflow-y-auto px-4 pb-4">
        {!loaded ? null : entries.length === 0 ? (
          <Placeholder icon={ClipboardIcon} text="Nothing copied yet — copies on either device will show up here" />
        ) : filtered.length === 0 ? (
          <Placeholder icon={SearchIcon} text="No matching items" />
        ) : (
          <>
            {pinnedItems.length > 0 && (
              <SectionLabel text="Pinned" />
            )}
            <ul className="space-y-2">
              <AnimatePresence initial={false}>
                {pinnedItems.map((entry, i) => (
                  <ClipboardRow
                    key={entry.id}
                    entry={entry}
                    index={i}
                    selected={i === selectedIndex}
                    shortcutNumber={i < 9 ? i + 1 : null}
                    justCopied={entry.id === justCopiedId}
                    onSelect={() => setSelectedIndex(i)}
                    onCopy={() => copyEntry(entry)}
                    onTogglePin={() => setClipboardEntryPinned(entry.id, !entry.isPinned)}
                  />
                ))}
              </AnimatePresence>
            </ul>

            {pinnedItems.length > 0 && recentItems.length > 0 && (
              <SectionLabel text="Recent" className="mt-4" />
            )}
            <ul className="mt-2 space-y-2">
              <AnimatePresence initial={false}>
                {recentItems.map((entry, i) => {
                  const globalIndex = pinnedItems.length + i;
                  return (
                    <ClipboardRow
                      key={entry.id}
                      entry={entry}
                      index={globalIndex}
                      selected={globalIndex === selectedIndex}
                      shortcutNumber={globalIndex < 9 ? globalIndex + 1 : null}
                      justCopied={entry.id === justCopiedId}
                      onSelect={() => setSelectedIndex(globalIndex)}
                      onCopy={() => copyEntry(entry)}
                      onTogglePin={() => setClipboardEntryPinned(entry.id, !entry.isPinned)}
                    />
                  );
                })}
              </AnimatePresence>
            </ul>
          </>
        )}
      </div>

      {filtered.length > 0 && (
        <div className="flex items-center justify-center gap-3 border-t border-black/5 dark:border-white/10 px-4 py-2 text-[11px] text-neutral-400 dark:text-neutral-500">
          <span>↑↓ navigate</span>
          <span>↵ copy</span>
          <span>⌘1–9 quick copy</span>
        </div>
      )}

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

function SectionLabel({ text, className = "" }: { text: string; className?: string }) {
  return (
    <h2 className={`px-1 pb-1.5 text-[11px] font-semibold uppercase tracking-wide text-neutral-400 dark:text-neutral-500 ${className}`}>
      {text}
    </h2>
  );
}

function TypeBadge({ entry }: { entry: ClipboardEntry }) {
  if (entry.isPinned) {
    return (
      <span className="flex h-7 w-9 shrink-0 items-center justify-center rounded-md bg-amber-500/15 text-amber-500">
        <Star className="h-3.5 w-3.5 fill-current" />
      </span>
    );
  }
  if (entry.imageBase64) {
    return (
      <span className="flex h-7 w-9 shrink-0 items-center justify-center rounded-md bg-black/5 dark:bg-white/10 text-neutral-500 dark:text-neutral-400">
        <ImageIcon className="h-3.5 w-3.5" />
      </span>
    );
  }
  return (
    <span className="flex h-7 w-9 shrink-0 items-center justify-center rounded-md bg-black/5 dark:bg-white/10 text-[10px] font-semibold tracking-wide text-neutral-500 dark:text-neutral-400">
      {entryTypeLabel(entry.text)}
    </span>
  );
}

function ClipboardRow({
  entry,
  index,
  selected,
  shortcutNumber,
  justCopied,
  onSelect,
  onCopy,
  onTogglePin,
}: {
  entry: ClipboardEntry;
  index: number;
  selected: boolean;
  shortcutNumber: number | null;
  justCopied: boolean;
  onSelect: () => void;
  onCopy: () => void;
  onTogglePin: () => void;
}) {
  const SourceIcon = entry.source === "mac" ? Laptop : Smartphone;

  return (
    <AnimatedListRow
      index={index}
      onClick={onSelect}
      className={`flex items-start gap-3 rounded-2xl border p-3 shadow-soft transition-colors ${
        selected
          ? "border-blue-500/30 bg-blue-500/[0.06] dark:bg-blue-400/[0.08]"
          : "border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900"
      }`}
    >
      <TypeBadge entry={entry} />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1.5">
          <SourceIcon className="h-3 w-3 shrink-0 text-neutral-400" />
          <span className="text-xs font-medium text-neutral-500 dark:text-neutral-400">
            {entry.source === "mac" ? "This Mac" : "Phone"}
          </span>
          <span className="text-xs text-neutral-300 dark:text-neutral-600">·</span>
          <span className="text-xs text-neutral-400 dark:text-neutral-500">{relativeTime(entry.timestamp)}</span>
        </div>
        {entry.imageBase64 ? (
          <img
            src={`data:image/png;base64,${entry.imageBase64}`}
            alt="Copied image"
            className="mt-1 max-h-32 rounded-lg border border-black/5 dark:border-white/10"
          />
        ) : (
          <p className="mt-0.5 whitespace-pre-wrap break-words line-clamp-4 text-[13px] text-neutral-800 dark:text-neutral-200">
            {entry.text}
          </p>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-1">
        {shortcutNumber && (
          <span className="rounded border border-black/10 dark:border-white/15 px-1.5 py-0.5 text-[10px] font-medium text-neutral-400 dark:text-neutral-500">
            ⌘{shortcutNumber}
          </span>
        )}
        <button
          onClick={(e) => {
            e.stopPropagation();
            onTogglePin();
          }}
          title={entry.isPinned ? "Unpin" : "Pin"}
          className={`transition-colors ${
            entry.isPinned
              ? "text-amber-500 hover:text-amber-600"
              : "text-neutral-300 hover:text-neutral-500 dark:text-neutral-600 dark:hover:text-neutral-300"
          }`}
        >
          <Star className={`h-3.5 w-3.5 ${entry.isPinned ? "fill-current" : ""}`} />
        </button>
        <button
          onClick={(e) => {
            e.stopPropagation();
            onCopy();
          }}
          title="Copy"
          className="text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-200 transition-colors"
        >
          {justCopied ? <Check className="h-3.5 w-3.5 text-emerald-500" /> : <Copy className="h-3.5 w-3.5" />}
        </button>
      </div>
    </AnimatedListRow>
  );
}
