import { useEffect, useMemo, useRef, useState } from "react";
import { motion } from "framer-motion";
import { EditorContent, useEditor, useEditorState, type Editor, type JSONContent } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import TaskList from "@tiptap/extension-task-list";
import TaskItem from "@tiptap/extension-task-item";
import TiptapImage from "@tiptap/extension-image";
import Placeholder from "@tiptap/extension-placeholder";
import { Extension } from "@tiptap/core";
import { Plugin, PluginKey } from "@tiptap/pm/state";
import { DecorationSet, Decoration } from "@tiptap/pm/view";
import {
  Plus,
  Trash2,
  FileText,
  Search as SearchIcon,
  Cloud,
  CloudOff,
  RefreshCw,
  Pin,
  PinOff,
  ChevronUp,
  ChevronDown,
  X,
  Check,
  ImagePlus,
  Bold,
  Italic,
  Strikethrough,
  Heading1,
  List,
  ListOrdered,
  ListChecks,
} from "lucide-react";
import { SectionHeader } from "./SectionHeader";
import { SearchBar } from "./SearchBar";
import { ResizableDivider } from "./ResizableDivider";
import { ConfirmDialog, Placeholder as EmptyState } from "./ContactDetail";
import { sectionMeta } from "../theme/sections";
import { relativeTime } from "../utils/relativeTime";
import {
  initNotesListeners,
  createNote,
  updateNote,
  deleteNote,
  setNotePinned,
  refreshNotes,
  isPendingNote,
  useNotesStore,
  type Note,
  type PendingNote,
} from "../store/notes";
import { useSyncSettingsStore } from "../store/syncSettings";

type NoteRowItem = (Note | PendingNote) & { pending: boolean };

/** A locally-created note (`isPendingNote`) never leaves the Mac, so it's always editable. For a
 *  phone-origin note, every mutation — pin, delete, and now title/body edits too — works even
 *  with no active connection: the Rust side (see `commands/notes.rs`) applies it locally right
 *  away and queues it for the phone the moment it's reachable again (`pending_note_mutations.rs`).
 *  All three are safe to replay blind: a pin toggle only ever has two states, a delete stays a
 *  delete, and a content edit is just the Mac's own last-written value winning outright — the
 *  right call for a personal single-user notes app with no live merge story. The only thing that
 *  still gates editing is the Notes *sync toggle* itself being off for a phone-origin note (a
 *  deliberate setting, not a connectivity blip). */
function canEdit(note: NoteRowItem, notesEnabled: boolean): boolean {
  return isPendingNote(note.id) || notesEnabled;
}

const AUTOSAVE_DELAY_MS = 600;

/** Two flat sections — Pinned first, then everything else by recency — rather than Apple Notes'
 *  date buckets (Today/Yesterday/…). */
function groupIntoSections(notes: NoteRowItem[]): [string, NoteRowItem[]][] {
  const pinned = notes.filter((n) => n.isPinned).sort((a, b) => b.updatedAt - a.updatedAt);
  const recents = notes.filter((n) => !n.isPinned).sort((a, b) => b.updatedAt - a.updatedAt);
  const sections: [string, NoteRowItem[]][] = [];
  if (pinned.length) sections.push(["Pinned", pinned]);
  if (recents.length) sections.push(["Recents", recents]);
  return sections;
}

/** Notes carry one cover image, stored as raw base64 (no data-URI prefix) so the exact same
 *  string round-trips through the Rust/Kotlin structs unchanged — this is the one place that
 *  prefix gets added back for `<img src>`. Always encoded as JPEG (see `compressImageFile`),
 *  regardless of the original file type, so this is safe to hardcode. */
function imageSrc(base64: string): string {
  return `data:image/jpeg;base64,${base64}`;
}

const MAX_IMAGE_DIMENSION = 1400;
const IMAGE_JPEG_QUALITY = 0.82;

/** Downscales/recompresses client-side before a note image ever touches the sync payload —
 *  without this, a straight-off-the-camera photo would bloat every autosave tick (the whole
 *  note, image included, resends on each edit) and the on-disk JSON on both platforms. */
function compressImageFile(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(reader.error ?? new Error("Could not read that file"));
    reader.onload = () => {
      const img = new Image();
      img.onerror = () => reject(new Error("Could not read that image"));
      img.onload = () => {
        const scale = Math.min(1, MAX_IMAGE_DIMENSION / Math.max(img.width, img.height));
        const width = Math.max(1, Math.round(img.width * scale));
        const height = Math.max(1, Math.round(img.height * scale));
        const canvas = document.createElement("canvas");
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext("2d");
        if (!ctx) {
          reject(new Error("Couldn't process that image"));
          return;
        }
        ctx.drawImage(img, 0, 0, width, height);
        const dataUrl = canvas.toDataURL("image/jpeg", IMAGE_JPEG_QUALITY);
        resolve(dataUrl.slice(dataUrl.indexOf(",") + 1));
      };
      img.src = reader.result as string;
    };
    reader.readAsDataURL(file);
  });
}

// --- Legacy body migration -----------------------------------------------------------------
// Every note's `body` used to be plain text with markdown-ish syntax (**bold**, - [ ] checklist,
// ![](data:...) inline images) — see git history for the old implementation. The editor is now a
// real rich-text document (TipTap/ProseMirror) and `body` is HTML, but nothing about the wire
// format itself changed: it's still just a `String` field on both Rust and Kotlin, sent through
// exactly the same create/update calls. A note saved before this change still has old-format text
// sitting in that field, so it needs a one-time, best-effort conversion into equivalent HTML the
// first time it's opened — after that, saving it back writes real HTML and the note never needs
// migrating again.
function looksLikeHtml(body: string): boolean {
  return /^\s*</.test(body);
}

// Builds the migrated note as a ProseMirror JSON document (node type names + attrs) instead of an
// HTML string. That's not a style choice — an earlier version of this hand-built raw HTML
// (`<li data-checked="...">`, guessing at TaskItem's exact expected markup) silently failed:
// TipTap's HTML *parser* didn't recognize the shape and produced an empty checklist plus the real
// text dumped into a plain bullet list instead. The JSON node-type/attrs shape below (`taskItem`
// with `attrs: { checked }`, etc.) is TipTap's own documented, stable schema representation, so
// there's no guessing at internal parseHTML rules involved.
const LEGACY_IMAGE_TOKEN_RE = /!\[\]\((data:image\/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+)\)/g;
const LEGACY_SOLO_IMAGE_RE = /^!\[\]\((data:image\/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+)\)$/;
const LEGACY_CHECKLIST_RE = /^-\s*\[([ xX])\]\s*(.*)$/;
const LEGACY_BULLET_RE = /^-\s+(.*)$/;
const LEGACY_NUMBERED_RE = /^\d+\.\s+(.*)$/;
const LEGACY_HEADING_RE = /^#\s+(.*)$/;
const LEGACY_INLINE_RE = /(\*\*)([^*]+?)\*\*|(~~)([^~]+?)~~|(\*)([^*]+?)\*/g;

/** Splits inline bold/italic/strikethrough markdown runs into ProseMirror text nodes carrying the
 *  corresponding mark. ProseMirror text nodes can't be empty, so a run with no text (an edge case
 *  from adjacent/empty markers) is dropped rather than included as invalid content. */
function parseInlineRuns(text: string): JSONContent[] {
  const runs: JSONContent[] = [];
  let cursor = 0;
  for (const m of text.matchAll(LEGACY_INLINE_RE)) {
    const idx = m.index ?? 0;
    if (idx > cursor) runs.push({ type: "text", text: text.slice(cursor, idx) });
    const [, boldMarker, boldContent, strikeMarker, strikeContent, , italicContent] = m;
    if (boldMarker) runs.push({ type: "text", text: boldContent, marks: [{ type: "bold" }] });
    else if (strikeMarker) runs.push({ type: "text", text: strikeContent, marks: [{ type: "strike" }] });
    else runs.push({ type: "text", text: italicContent, marks: [{ type: "italic" }] });
    cursor = idx + m[0].length;
  }
  if (cursor < text.length) runs.push({ type: "text", text: text.slice(cursor) });
  return runs.filter((r) => (r.text?.length ?? 0) > 0);
}

function inlineOrEmpty(text: string): JSONContent[] | undefined {
  const runs = parseInlineRuns(text);
  return runs.length > 0 ? runs : undefined;
}

type OpenListType = "bulletList" | "orderedList" | "taskList";

function migrateLegacyBodyToDoc(body: string): JSONContent {
  const content: JSONContent[] = [];
  let openList: { type: OpenListType; items: JSONContent[] } | null = null;
  function closeList() {
    if (!openList) return;
    content.push({ type: openList.type, content: openList.items });
    openList = null;
  }
  function ensureList(type: OpenListType) {
    if (openList?.type !== type) {
      closeList();
      openList = { type, items: [] };
    }
    return openList;
  }

  for (const rawLine of body.split("\n")) {
    const trimmed = rawLine.trim();
    const soloImage = LEGACY_SOLO_IMAGE_RE.exec(trimmed);
    if (soloImage) {
      closeList();
      content.push({ type: "image", attrs: { src: soloImage[1] } });
      continue;
    }
    if (trimmed === "") {
      closeList();
      continue;
    }

    const checklistMatch = LEGACY_CHECKLIST_RE.exec(trimmed);
    const bulletMatch = !checklistMatch && LEGACY_BULLET_RE.exec(trimmed);
    const numberedMatch = !checklistMatch && !bulletMatch && LEGACY_NUMBERED_RE.exec(trimmed);
    const headingMatch = !checklistMatch && !bulletMatch && !numberedMatch && LEGACY_HEADING_RE.exec(trimmed);

    if (checklistMatch) {
      const checked = checklistMatch[1].toLowerCase() === "x";
      ensureList("taskList")!.items.push({
        type: "taskItem",
        attrs: { checked },
        content: [{ type: "paragraph", content: inlineOrEmpty(checklistMatch[2]) }],
      });
    } else if (bulletMatch) {
      ensureList("bulletList")!.items.push({
        type: "listItem",
        content: [{ type: "paragraph", content: inlineOrEmpty(bulletMatch[1]) }],
      });
    } else if (numberedMatch) {
      ensureList("orderedList")!.items.push({
        type: "listItem",
        content: [{ type: "paragraph", content: inlineOrEmpty(numberedMatch[1]) }],
      });
    } else if (headingMatch) {
      closeList();
      content.push({ type: "heading", attrs: { level: 1 }, content: inlineOrEmpty(headingMatch[1]) });
    } else {
      closeList();
      // Any other inline image token mid-line (rare — legacy notes essentially always split
      // images onto their own line, handled above) is just dropped from the plain paragraph
      // rather than guessed at further.
      content.push({ type: "paragraph", content: inlineOrEmpty(trimmed.replace(LEGACY_IMAGE_TOKEN_RE, "")) });
    }
  }
  closeList();
  return { type: "doc", content: content.length > 0 ? content : [{ type: "paragraph" }] };
}

/** What actually gets handed to `useEditor({ content: ... })`. A legacy `imageBase64` cover image
 *  (this app's feature before inline images existed) only ever needs prepending when `body` is
 *  still in the old plain-text format — once a note has been through this editor and saved back
 *  as HTML, the field stops being written at all (see `createNote`/`updateNote` callers below,
 *  which always pass `null` for it). A body that's already HTML (this editor's own prior output)
 *  is passed straight through as a string — TipTap's HTML parser round-trips its *own* output
 *  reliably; it was only ever a hand-built migration guess that it choked on. */
function buildInitialContent(body: string, legacyImage: string | null): string | JSONContent {
  if (looksLikeHtml(body)) return body;
  const doc = migrateLegacyBodyToDoc(body);
  if (legacyImage) {
    doc.content = [{ type: "image", attrs: { src: imageSrc(legacyImage) } }, ...(doc.content ?? [])];
  }
  return doc;
}

interface PreviewLine {
  text: string;
  /** `undefined` = a plain text line; `true`/`false` = a checklist item and whether it's done. */
  checked?: boolean;
}

const MAX_PREVIEW_LINES = 4;

/** Parses the note's HTML body with the browser's own HTML parser (not regex) into card-preview
 *  lines and a cover image. Headings/lists/checkboxes/images are real elements now, so this just
 *  walks the parsed tree instead of pattern-matching markdown syntax. */
function parseNotePreview(body: string): { lines: PreviewLine[]; coverImage: string | null } {
  const container = document.createElement("div");
  container.innerHTML = body;

  const coverImage = container.querySelector("img")?.getAttribute("src") ?? null;

  const lines: PreviewLine[] = [];
  for (const el of Array.from(container.querySelectorAll("p, h1, li"))) {
    if (lines.length >= MAX_PREVIEW_LINES) break;
    // A <p> nested inside a list item is already covered by walking that <li> directly (via
    // textContent below) — counting it again as its own top-level line would duplicate it.
    if (el.tagName === "P" && el.closest("li")) continue;
    const isTaskItem = el.tagName === "LI" && el.hasAttribute("data-checked");
    // A task item's <li> also contains a visually-hidden accessibility label ("Task item
    // checkbox for …") alongside the real text — el.textContent would pick up both, since that
    // label is only hidden with CSS, not actually removed from the DOM. Its real content lives
    // in the <div> that follows the <label>, so read from there instead of the whole <li>.
    const text = (isTaskItem ? el.querySelector(":scope > div") : el)?.textContent?.trim() ?? "";
    if (!text) continue;
    if (isTaskItem) {
      lines.push({ text, checked: el.getAttribute("data-checked") === "true" });
    } else {
      lines.push({ text });
    }
  }
  return { lines, coverImage };
}

// --- Find & replace over the live ProseMirror document --------------------------------------
interface DocMatch {
  from: number;
  to: number;
}

/** Case-insensitive, non-overlapping substring search over the document's text nodes. A query
 *  spanning two adjacent text nodes (e.g. crossing a bold/plain boundary within one paragraph)
 *  won't match — a narrow limitation carried over from the old block-based search rather than a
 *  newly introduced one. */
function findDocMatches(editor: Editor, query: string): DocMatch[] {
  if (!query) return [];
  const needle = query.toLowerCase();
  const matches: DocMatch[] = [];
  editor.state.doc.descendants((node, pos) => {
    if (!node.isText || !node.text) return;
    const haystack = node.text.toLowerCase();
    let from = 0;
    while (from <= haystack.length - needle.length) {
      const idx = haystack.indexOf(needle, from);
      if (idx === -1) break;
      matches.push({ from: pos + idx, to: pos + idx + needle.length });
      from = idx + needle.length;
    }
  });
  return matches;
}

interface SearchHighlightStorage {
  matches: DocMatch[];
  activeIndex: number;
}

// TipTap's own `Storage` interface is empty by default — this is its documented pattern for
// giving `editor.storage.searchHighlight` a real type instead of `any`.
declare module "@tiptap/core" {
  interface Storage {
    searchHighlight: SearchHighlightStorage;
  }
}

/** Renders find/replace matches as decorations directly over the live document. Driven by
 *  mutating this extension's own storage (see the effect in NoteEditPanel that does this) plus a
 *  no-op transaction dispatch to make ProseMirror recompute and repaint them — these highlights
 *  are UI-only and have no business becoming part of the document's own undo history, which is
 *  why they're plain storage rather than editor state. */
const SearchHighlight = Extension.create<Record<string, never>, SearchHighlightStorage>({
  name: "searchHighlight",
  addStorage() {
    return { matches: [], activeIndex: -1 };
  },
  addProseMirrorPlugins() {
    const extensionStorage = this.storage;
    return [
      new Plugin({
        key: new PluginKey("searchHighlight"),
        props: {
          decorations(state) {
            const { matches, activeIndex } = extensionStorage;
            if (matches.length === 0) return null;
            return DecorationSet.create(
              state.doc,
              matches.map((m, i) =>
                Decoration.inline(m.from, m.to, {
                  class:
                    i === activeIndex
                      ? "search-match-active rounded-[2px] bg-red-500 px-0.5 text-white"
                      : "rounded-[2px] underline decoration-red-500 decoration-2 underline-offset-2",
                }),
              ),
            );
          },
        },
      }),
    ];
  },
});

export function NotesView() {
  const { notes, pending, loaded, lastError, pendingMutationIds } = useNotesStore();
  const notesEnabled = useSyncSettingsStore((s) => s.settings.notesEnabled);
  const [searchText, setSearchText] = useState("");
  const [listWidth, setListWidth] = useState(320);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [isCreating, setIsCreating] = useState(false);
  const [sessionKey, setSessionKey] = useState(0);
  const creatingBaselineIds = useRef<Set<string> | null>(null);

  useEffect(() => {
    initNotesListeners();
  }, []);

  // A synced (phone-origin) note counts as "pending"/unsynced too when it has a queued mutation
  // from an edit made while the phone was unreachable — not just the always-local `pending` array.
  const combined: NoteRowItem[] = useMemo(() => {
    const items = [
      ...pending.map((n) => ({ ...n, pending: true })),
      ...notes.map((n) => ({ ...n, pending: pendingMutationIds.has(n.id) })),
    ];
    return items.sort((a, b) => b.updatedAt - a.updatedAt);
  }, [notes, pending, pendingMutationIds]);

  // A freshly-created draft has no id until the create round-trips and a `notes-updated` /
  // `local-notes-updated` event lands. Once it shows up (an id we didn't have before we started
  // creating), switch the panel over to editing that note in place.
  useEffect(() => {
    if (!isCreating || !creatingBaselineIds.current) return;
    const created = combined.find((n) => !creatingBaselineIds.current!.has(n.id));
    if (created) {
      creatingBaselineIds.current = null;
      setIsCreating(false);
      setSelectedId(created.id);
    }
  }, [combined, isCreating]);

  const filtered = useMemo(() => {
    if (!searchText.trim()) return combined;
    const q = searchText.toLowerCase();
    return combined.filter((n) => n.title.toLowerCase().includes(q) || n.body.toLowerCase().includes(q));
  }, [combined, searchText]);

  const sections = useMemo(() => groupIntoSections(filtered), [filtered]);

  const selected = combined.find((n) => n.id === selectedId) ?? null;

  function selectNote(id: string) {
    creatingBaselineIds.current = null;
    setIsCreating(false);
    setSelectedId(id);
    setSessionKey((k) => k + 1);
  }

  function startNewNote() {
    creatingBaselineIds.current = new Set(combined.map((n) => n.id));
    setIsCreating(true);
    setSelectedId(null);
    setSessionKey((k) => k + 1);
  }

  async function handleDeleteNote(id: string) {
    await deleteNote(id);
    if (selectedId === id) setSelectedId(null);
  }

  return (
    <div className="flex h-full flex-col">
      <SectionHeader
        section={sectionMeta("notes")}
        subtitle={`${combined.length} note${combined.length === 1 ? "" : "s"}`}
        trailing={
          <div className="flex items-center gap-1.5">
            <button
              onClick={() => refreshNotes()}
              title="Sync"
              className="rounded-md p-1.5 text-neutral-500 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
            >
              <RefreshCw className="h-3.5 w-3.5" />
            </button>
            <button
              onClick={startNewNote}
              className="flex items-center gap-1 rounded-md border border-black/10 dark:border-white/15 px-2.5 py-1 text-xs font-medium text-neutral-600 dark:text-neutral-300 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
            >
              <Plus className="h-3.5 w-3.5" />
              New Note
            </button>
          </div>
        }
      />

      {!notesEnabled && (
        <div className="mx-4 mt-3 flex items-center gap-2 rounded-lg bg-orange-500/10 px-3 py-2 text-xs text-orange-700 dark:text-orange-400">
          <CloudOff className="h-3.5 w-3.5 shrink-0" />
          Notes sync is off — new notes stay on this Mac and sync to your phone once it's back on.
        </div>
      )}

      {lastError && (
        <div className="mx-4 mt-3 flex items-center gap-2 rounded-lg bg-orange-500/10 px-3 py-2 text-xs text-orange-700 dark:text-orange-400">
          {lastError}
        </div>
      )}

      <div className="flex flex-1 overflow-hidden">
        <div className="flex flex-col" style={{ width: listWidth }}>
          <SearchBar value={searchText} onChange={setSearchText} placeholder="Search notes" />
          <div className="flex-1 overflow-y-auto">
            {!loaded ? null : combined.length === 0 ? (
              <EmptyState icon={FileText} text="No notes yet" />
            ) : filtered.length === 0 ? (
              <EmptyState icon={SearchIcon} text="No matching notes" />
            ) : (
              sections.map(([label, items]) => (
                <div key={label}>
                  <div className="sticky top-0 z-10 bg-white/90 dark:bg-neutral-950/90 backdrop-blur px-3 py-1 text-[11px] font-semibold uppercase tracking-wide text-neutral-400">
                    {label}
                  </div>
                  <div className="px-3 pt-2" style={{ columnWidth: 148, columnGap: 12 }}>
                    {items.map((note) => (
                      <NoteCard
                        key={note.id}
                        note={note}
                        selected={note.id === selectedId}
                        canEdit={canEdit(note, notesEnabled)}
                        onClick={() => selectNote(note.id)}
                        onDelete={() => handleDeleteNote(note.id)}
                      />
                    ))}
                  </div>
                </div>
              ))
            )}
          </div>
        </div>

        <ResizableDivider width={listWidth} onWidthChange={setListWidth} minWidth={260} maxWidth={620} />

        <div className="m-3 flex-1 overflow-hidden rounded-2xl border border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900 shadow-soft">
          {isCreating ? (
            <NoteEditPanel
              key={sessionKey}
              noteId={null}
              isDraft
              initialTitle=""
              initialBody=""
              initialImage={null}
              canEdit
              updatedAt={null}
            />
          ) : selected ? (
            <NoteEditPanel
              key={sessionKey}
              noteId={selected.id}
              isDraft={false}
              initialTitle={selected.title}
              initialBody={selected.body}
              initialImage={selected.imageBase64 ?? null}
              canEdit={canEdit(selected, notesEnabled)}
              updatedAt={selected.updatedAt}
            />
          ) : (
            <EmptyState icon={FileText} text="Select a note" />
          )}
        </div>
      </div>
    </div>
  );
}

function NoteCard({
  note,
  selected,
  canEdit,
  onClick,
  onDelete,
}: {
  note: NoteRowItem;
  selected: boolean;
  canEdit: boolean;
  onClick: () => void;
  onDelete: () => void;
}) {
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const { lines: previewLines, coverImage: parsedCover } = useMemo(() => parseNotePreview(note.body), [note.body]);
  const coverImage = parsedCover ?? (note.imageBase64 ? imageSrc(note.imageBase64) : null);

  return (
    <div className="mb-3 break-inside-avoid">
      <div
        role="button"
        tabIndex={0}
        onClick={onClick}
        onKeyDown={(e) => {
          if (e.key === "Enter") onClick();
        }}
        className={`group relative flex w-full flex-col overflow-hidden rounded-2xl border text-left shadow-soft transition-all hover:-translate-y-0.5 hover:shadow-soft-hover ${
          selected
            ? "border-yellow-500/50 bg-yellow-500/[0.06] ring-1 ring-yellow-500/40"
            : "border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900 hover:bg-black/[0.015] dark:hover:bg-white/[0.03]"
        }`}
      >
        {coverImage && <img src={coverImage} alt="" className="h-28 w-full shrink-0 object-cover" />}

        {canEdit && (
          <div className="absolute right-1.5 top-1.5 flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
            <RowIconButton
              icon={note.isPinned ? PinOff : Pin}
              tint="#eab308"
              title={note.isPinned ? "Unpin" : "Pin"}
              onClick={(e) => {
                e.stopPropagation();
                setNotePinned(note.id, !note.isPinned);
              }}
            />
            <RowIconButton
              icon={Trash2}
              tint="#ef4444"
              title="Delete"
              onClick={(e) => {
                e.stopPropagation();
                setConfirmingDelete(true);
              }}
            />
          </div>
        )}

        <div className="flex flex-col gap-1 px-3 py-2.5">
          <span className="flex items-start gap-1.5">
            {note.isPinned && <Pin className="mt-0.5 h-3 w-3 shrink-0 fill-current text-neutral-400" />}
            <span className="line-clamp-2 text-[13px] font-semibold text-neutral-900 dark:text-neutral-100">
              {note.title.trim() || "Untitled"}
            </span>
          </span>

          {previewLines.length > 0 && (
            <div className="flex flex-col gap-0.5">
              {previewLines.map((line, i) =>
                line.checked === undefined ? (
                  <p key={i} className="line-clamp-2 text-xs text-neutral-500 dark:text-neutral-400">
                    {line.text}
                  </p>
                ) : (
                  <span key={i} className="flex items-start gap-1.5">
                    <span
                      className={`mt-0.5 flex h-3 w-3 shrink-0 items-center justify-center rounded-[3px] border ${
                        line.checked
                          ? "border-yellow-500 bg-yellow-500 text-white"
                          : "border-neutral-400 dark:border-neutral-600"
                      }`}
                    >
                      {line.checked && <Check className="h-2 w-2" strokeWidth={3} />}
                    </span>
                    <span
                      className={`line-clamp-1 text-xs ${
                        line.checked
                          ? "text-neutral-400 line-through dark:text-neutral-500"
                          : "text-neutral-600 dark:text-neutral-300"
                      }`}
                    >
                      {line.text}
                    </span>
                  </span>
                ),
              )}
            </div>
          )}

          <span className="mt-0.5 flex flex-wrap items-center gap-1.5 text-[11px] text-neutral-400 dark:text-neutral-500">
            {relativeTime(note.updatedAt)}
            <span
              title={note.pending ? "Not synced with your phone yet" : "Synced with your phone"}
              className="flex shrink-0 items-center"
            >
              {note.pending ? (
                <CloudOff className="h-3 w-3 text-orange-500 dark:text-orange-400" />
              ) : (
                <Cloud className="h-3 w-3 text-neutral-300 dark:text-neutral-600" />
              )}
            </span>
          </span>
        </div>
      </div>

      {confirmingDelete && (
        <ConfirmDialog
          title={`Delete "${note.title.trim() || "Untitled"}"?`}
          message="This can't be undone."
          onCancel={() => setConfirmingDelete(false)}
          onConfirm={() => {
            setConfirmingDelete(false);
            onDelete();
          }}
        />
      )}
    </div>
  );
}

/** Always-editable note panel: typing autosaves after a short pause, no explicit Save step.
 *  For a brand-new note (`isDraft`), the first non-empty edit creates it; further edits made
 *  before the real id comes back are flushed once `noteId` arrives (see the effect below).
 *
 *  The body is now a single TipTap (ProseMirror) rich-text document instead of a plain string
 *  with markdown-ish syntax — bold/italic/strike/headings/lists/checklists/images are real nodes,
 *  and `editor.getHTML()` is what gets sent as `body` on every autosave (see buildInitialContent /
 *  migrateLegacyBodyToDoc above for how an older, pre-rich-text note gets converted the first
 *  time it's opened). */
function NoteEditPanel({
  noteId,
  isDraft,
  initialTitle,
  initialBody,
  initialImage,
  canEdit,
  updatedAt,
}: {
  noteId: string | null;
  isDraft: boolean;
  initialTitle: string;
  initialBody: string;
  initialImage: string | null;
  canEdit: boolean;
  updatedAt: number | null;
}) {
  const [title, setTitle] = useState(initialTitle);
  const [imageDragOver, setImageDragOver] = useState(false);
  const [imageError, setImageError] = useState<string | null>(null);
  const [bodyVersion, setBodyVersion] = useState(0);

  const dirtyRef = useRef(false);
  const createdRef = useRef(false);
  const latestRef = useRef({ title, isDraft, noteId });
  latestRef.current = { title, isDraft, noteId };

  const editor = useEditor({
    editable: canEdit,
    content: buildInitialContent(initialBody, initialImage),
    extensions: [
      StarterKit.configure({
        heading: { levels: [1] },
        codeBlock: false,
        blockquote: false,
        horizontalRule: false,
        code: false,
      }),
      TaskList,
      TaskItem.configure({ nested: false }),
      TiptapImage,
      Placeholder.configure({ placeholder: "Start typing…" }),
      SearchHighlight,
    ],
    editorProps: {
      attributes: { class: "note-rich-content focus:outline-none" },
    },
    onUpdate: () => {
      dirtyRef.current = true;
      setBodyVersion((v) => v + 1);
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function flush() {
    if (!editor) return;
    const { title, isDraft, noteId } = latestRef.current;
    const t = title.trim();
    const b = editor.isEmpty ? "" : editor.getHTML();
    if (!t && !b) return;
    if (isDraft) {
      if (!createdRef.current) {
        // Set before the call resolves so a second flush (e.g. the unmount-time one) can't fire
        // a duplicate create while this one's in flight; reset on failure so the next autosave
        // tick retries instead of the draft being silently stuck forever.
        createdRef.current = true;
        createNote(t, b, null).then((ok) => {
          if (!ok) createdRef.current = false;
        });
      }
    } else if (noteId) {
      updateNote(noteId, t, b, null);
    }
  }

  useEffect(() => {
    editor?.setEditable(canEdit);
  }, [editor, canEdit]);

  async function insertImageFile(file: File | null | undefined) {
    if (!file || !editor) return;
    if (!file.type.startsWith("image/")) {
      setImageError("That's not an image file");
      setTimeout(() => setImageError(null), 3000);
      return;
    }
    try {
      const base64 = await compressImageFile(file);
      editor.chain().focus().setImage({ src: imageSrc(base64) }).run();
    } catch {
      setImageError("Couldn't read that image");
      setTimeout(() => setImageError(null), 3000);
    }
  }

  useEffect(() => {
    if (!dirtyRef.current) return;
    const timer = setTimeout(flush, AUTOSAVE_DELAY_MS);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title, bodyVersion, isDraft, noteId]);

  // Flush on unmount too, so switching notes right after typing doesn't drop the last edit.
  useEffect(() => {
    return () => {
      if (dirtyRef.current) flush();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // --- Formatting toolbar state -------------------------------------------------------------
  const toolbarState = useEditorState({
    editor,
    selector: (ctx) => ({
      bold: ctx.editor?.isActive("bold") ?? false,
      italic: ctx.editor?.isActive("italic") ?? false,
      strike: ctx.editor?.isActive("strike") ?? false,
      heading: ctx.editor?.isActive("heading", { level: 1 }) ?? false,
      taskList: ctx.editor?.isActive("taskList") ?? false,
      bulletList: ctx.editor?.isActive("bulletList") ?? false,
      orderedList: ctx.editor?.isActive("orderedList") ?? false,
    }),
  });

  // --- Find & replace, scoped to this note's document ---------------------------------------
  const [findOpen, setFindOpen] = useState(false);
  const [findQuery, setFindQuery] = useState("");
  const [replaceQuery, setReplaceQuery] = useState("");
  const [matchIndex, setMatchIndex] = useState(0);
  const findInputRef = useRef<HTMLInputElement>(null);

  const matches = useMemo(
    () => (editor ? findDocMatches(editor, findQuery) : []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [editor, findQuery, bodyVersion],
  );

  useEffect(() => setMatchIndex(0), [findQuery]);
  useEffect(() => {
    setMatchIndex((i) => Math.min(i, Math.max(0, matches.length - 1)));
  }, [matches.length]);

  useEffect(() => {
    if (!findOpen) return;
    findInputRef.current?.focus();
    findInputRef.current?.select();
  }, [findOpen]);

  // Drives the SearchHighlight extension's decorations from plain mutable storage (see that
  // extension's own comment) and scrolls the active match into view — mirrors the old
  // activeMarkRef.scrollIntoView, just against the editor's real DOM instead of a plain <mark>.
  useEffect(() => {
    if (!editor) return;
    editor.storage.searchHighlight.matches = findOpen ? matches : [];
    editor.storage.searchHighlight.activeIndex = matchIndex;
    editor.view.dispatch(editor.state.tr);
    if (findOpen && matches.length > 0) {
      requestAnimationFrame(() => {
        editor.view.dom.querySelector(".search-match-active")?.scrollIntoView({ block: "center" });
      });
    }
  }, [editor, matches, matchIndex, findOpen]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      const meta = e.metaKey || e.ctrlKey;
      if (meta && e.key.toLowerCase() === "f") {
        e.preventDefault();
        setFindOpen(true);
      } else if (e.key === "Escape" && findOpen) {
        e.preventDefault();
        closeFind();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [findOpen]);

  function closeFind() {
    setFindOpen(false);
    setFindQuery("");
    setReplaceQuery("");
  }

  function goNext() {
    if (matches.length === 0) return;
    setMatchIndex((i) => (i + 1) % matches.length);
  }

  function goPrev() {
    if (matches.length === 0) return;
    setMatchIndex((i) => (i - 1 + matches.length) % matches.length);
  }

  function replaceCurrent() {
    if (!canEdit || !editor || matches.length === 0) return;
    const m = matches[matchIndex];
    const chain = editor.chain().focus();
    if (replaceQuery === "") chain.deleteRange(m).run();
    else chain.insertContentAt(m, { type: "text", text: replaceQuery }).run();
  }

  function replaceAll() {
    if (!canEdit || !editor || matches.length === 0) return;
    // Reverse order so each match's precomputed {from, to} stays valid as earlier (larger-
    // position) replacements shift everything after them — nothing after the *first* remaining
    // match ever needs its position adjusted this way.
    let chain = editor.chain().focus();
    for (let i = matches.length - 1; i >= 0; i--) {
      const m = matches[i];
      chain = replaceQuery === "" ? chain.deleteRange(m) : chain.insertContentAt(m, { type: "text", text: replaceQuery });
    }
    chain.run();
    setMatchIndex(0);
  }

  if (!editor) return null;

  return (
    <div className="relative flex h-full flex-col">
      <div className="border-b border-black/5 dark:border-white/10">
        <p className="px-6 pb-1 pt-3 text-xs text-neutral-400 dark:text-neutral-500">
          {updatedAt != null ? `Last edited ${relativeTime(updatedAt)}` : ""}
        </p>
        <FormattingToolbar
          canEdit={canEdit}
          state={toolbarState}
          onBold={() => editor.chain().focus().toggleBold().run()}
          onItalic={() => editor.chain().focus().toggleItalic().run()}
          onStrike={() => editor.chain().focus().toggleStrike().run()}
          onHeading={() => editor.chain().focus().toggleHeading({ level: 1 }).run()}
          onChecklist={() => editor.chain().focus().toggleTaskList().run()}
          onBulletList={() => editor.chain().focus().toggleBulletList().run()}
          onOrderedList={() => editor.chain().focus().toggleOrderedList().run()}
          onInsertImage={insertImageFile}
          onOpenFind={() => setFindOpen(true)}
        />
      </div>

      <div className="flex-1 overflow-y-auto">
        <motion.div
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.2 }}
          onDragOver={(e) => {
            if (!canEdit) return;
            e.preventDefault();
            setImageDragOver(true);
          }}
          onDragLeave={() => setImageDragOver(false)}
          onDrop={(e) => {
            e.preventDefault();
            setImageDragOver(false);
            if (canEdit) insertImageFile(e.dataTransfer.files?.[0]);
          }}
          onPaste={(e) => {
            if (!canEdit) return;
            const imageItem = Array.from(e.clipboardData?.items ?? []).find((item) =>
              item.type.startsWith("image/"),
            );
            const file = imageItem?.getAsFile();
            if (file) {
              e.preventDefault();
              insertImageFile(file);
            }
          }}
          className={`relative flex flex-1 flex-col gap-3 px-6 pb-6 pt-6 ${
            imageDragOver ? "ring-2 ring-inset ring-yellow-500/50" : ""
          }`}
        >
          {imageError && <p className="text-xs text-red-500 dark:text-red-400">{imageError}</p>}

          <input
            autoFocus
            value={title}
            disabled={!canEdit}
            onChange={(e) => {
              setTitle(e.target.value);
              dirtyRef.current = true;
            }}
            placeholder="Title"
            className="w-full bg-transparent text-xl font-bold text-neutral-900 dark:text-neutral-100 placeholder:text-neutral-400 focus:outline-none disabled:opacity-60"
          />

          <EditorContent editor={editor} />
        </motion.div>
      </div>

      {findOpen && (
        <FindReplaceBar
          inputRef={findInputRef}
          findQuery={findQuery}
          onFindQueryChange={setFindQuery}
          replaceQuery={replaceQuery}
          onReplaceQueryChange={setReplaceQuery}
          matchCount={matches.length}
          matchIndex={matchIndex}
          canEdit={canEdit}
          onNext={goNext}
          onPrev={goPrev}
          onReplace={replaceCurrent}
          onReplaceAll={replaceAll}
          onClose={closeFind}
        />
      )}
    </div>
  );
}

function ImagePicker({ onPick }: { onPick: (file: File | null | undefined) => void }) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  return (
    <>
      <input
        ref={fileInputRef}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={(e) => {
          onPick(e.target.files?.[0]);
          e.target.value = "";
        }}
      />
      <ToolbarButton icon={ImagePlus} title="Insert image" onClick={() => fileInputRef.current?.click()} />
    </>
  );
}

interface ToolbarActiveState {
  bold: boolean;
  italic: boolean;
  strike: boolean;
  heading: boolean;
  taskList: boolean;
  bulletList: boolean;
  orderedList: boolean;
}

/** Pinned above the scrollable body (a sibling of the scroll container, not inside it) so it
 *  stays put regardless of scroll position, the way a toolbar in a full-featured notes app does —
 *  unlike FindReplaceBar, which sits at the bottom of the note's own content and can scroll out of
 *  view for a long note. Search stays available even for a read-only (canEdit false) note — only
 *  the buttons that would actually change the note's content are hidden in that case. */
function FormattingToolbar({
  canEdit,
  state,
  onBold,
  onItalic,
  onStrike,
  onHeading,
  onChecklist,
  onBulletList,
  onOrderedList,
  onInsertImage,
  onOpenFind,
}: {
  canEdit: boolean;
  state: ToolbarActiveState;
  onBold: () => void;
  onItalic: () => void;
  onStrike: () => void;
  onHeading: () => void;
  onChecklist: () => void;
  onBulletList: () => void;
  onOrderedList: () => void;
  onInsertImage: (file: File | null | undefined) => void;
  onOpenFind: () => void;
}) {
  return (
    <div className="flex items-center gap-0.5 px-4 pb-2">
      {canEdit && (
        <>
          <ToolbarButton icon={Bold} title="Bold (⌘B)" active={state.bold} onClick={onBold} />
          <ToolbarButton icon={Italic} title="Italic (⌘I)" active={state.italic} onClick={onItalic} />
          <ToolbarButton icon={Strikethrough} title="Strikethrough" active={state.strike} onClick={onStrike} />
          <div className="mx-1.5 h-4 w-px bg-black/10 dark:bg-white/10" />
          <ToolbarButton icon={Heading1} title="Heading" active={state.heading} onClick={onHeading} />
          <ToolbarButton icon={ListChecks} title="Checklist" active={state.taskList} onClick={onChecklist} />
          <ToolbarButton icon={List} title="Bulleted list" active={state.bulletList} onClick={onBulletList} />
          <ToolbarButton icon={ListOrdered} title="Numbered list" active={state.orderedList} onClick={onOrderedList} />
          <div className="mx-1.5 h-4 w-px bg-black/10 dark:bg-white/10" />
          <ImagePicker onPick={onInsertImage} />
        </>
      )}
      <div className="flex-1" />
      <ToolbarButton icon={SearchIcon} title="Find & Replace (⌘F)" onClick={onOpenFind} />
    </div>
  );
}

function ToolbarButton({
  icon: Icon,
  title,
  active,
  onClick,
}: {
  icon: typeof Bold;
  title: string;
  active?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      // A toolbar click would otherwise blur the editor and drop its selection before the click
      // handler even runs — ProseMirror keeps its last selection internally regardless, but this
      // avoids the visible focus flicker and matches TipTap's own documented toolbar pattern.
      onMouseDown={(e) => e.preventDefault()}
      onClick={onClick}
      title={title}
      className={`flex h-7 w-7 items-center justify-center rounded-md transition-colors ${
        active
          ? "bg-yellow-500/15 text-yellow-600 dark:text-yellow-400"
          : "text-neutral-500 dark:text-neutral-400 hover:bg-black/5 dark:hover:bg-white/10"
      }`}
    >
      <Icon className="h-4 w-4" />
    </button>
  );
}

function FindReplaceBar({
  inputRef,
  findQuery,
  onFindQueryChange,
  replaceQuery,
  onReplaceQueryChange,
  matchCount,
  matchIndex,
  canEdit,
  onNext,
  onPrev,
  onReplace,
  onReplaceAll,
  onClose,
}: {
  inputRef: React.RefObject<HTMLInputElement | null>;
  findQuery: string;
  onFindQueryChange: (v: string) => void;
  replaceQuery: string;
  onReplaceQueryChange: (v: string) => void;
  matchCount: number;
  matchIndex: number;
  canEdit: boolean;
  onNext: () => void;
  onPrev: () => void;
  onReplace: () => void;
  onReplaceAll: () => void;
  onClose: () => void;
}) {
  return (
    <div className="absolute inset-x-4 bottom-4 z-20 flex flex-wrap items-center gap-2 rounded-xl border border-black/10 dark:border-white/10 bg-white/95 dark:bg-neutral-800/95 backdrop-blur px-3 py-2 shadow-modal">
      <SearchIcon className="h-3.5 w-3.5 shrink-0 text-neutral-400" />
      <input
        ref={inputRef}
        value={findQuery}
        onChange={(e) => onFindQueryChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            if (e.shiftKey) onPrev();
            else onNext();
          }
        }}
        placeholder="Find"
        className="min-w-[80px] flex-1 bg-transparent text-[13px] text-neutral-900 dark:text-neutral-100 placeholder:text-neutral-400 focus:outline-none"
      />
      <span className="shrink-0 text-xs tabular-nums text-neutral-400">
        {matchCount === 0 ? "0 of 0" : `${matchIndex + 1} of ${matchCount}`}
      </span>
      <div className="flex shrink-0 items-center gap-0.5">
        <FindBarIconButton icon={ChevronUp} onClick={onPrev} disabled={matchCount === 0} title="Previous match" />
        <FindBarIconButton icon={ChevronDown} onClick={onNext} disabled={matchCount === 0} title="Next match" />
      </div>

      <div className="h-4 w-px shrink-0 bg-black/10 dark:bg-white/10" />

      <input
        value={replaceQuery}
        disabled={!canEdit}
        onChange={(e) => onReplaceQueryChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            onReplace();
          }
        }}
        placeholder="Replace"
        className="min-w-[80px] flex-1 bg-transparent text-[13px] text-neutral-900 dark:text-neutral-100 placeholder:text-neutral-400 focus:outline-none disabled:opacity-50"
      />
      <button
        onClick={onReplace}
        disabled={!canEdit || matchCount === 0}
        className="shrink-0 rounded-lg border border-black/10 dark:border-white/15 px-2.5 py-1 text-xs font-medium text-neutral-700 dark:text-neutral-200 disabled:opacity-40 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
      >
        Replace
      </button>
      <button
        onClick={onReplaceAll}
        disabled={!canEdit || matchCount === 0}
        className="shrink-0 rounded-lg bg-yellow-500 px-2.5 py-1 text-xs font-medium text-white disabled:opacity-40 hover:bg-yellow-600 transition-colors"
      >
        Replace All
      </button>
      <button
        onClick={onClose}
        title="Close (Esc)"
        className="shrink-0 rounded-md p-1 text-neutral-400 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
      >
        <X className="h-3.5 w-3.5" />
      </button>
    </div>
  );
}

function FindBarIconButton({
  icon: Icon,
  onClick,
  disabled,
  title,
}: {
  icon: typeof ChevronUp;
  onClick: () => void;
  disabled?: boolean;
  title: string;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={title}
      className="flex h-6 w-6 items-center justify-center rounded-md text-neutral-500 disabled:opacity-30 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
    >
      <Icon className="h-3.5 w-3.5" />
    </button>
  );
}

function RowIconButton({
  icon: Icon,
  tint,
  title,
  onClick,
}: {
  icon: typeof Pin;
  tint: string;
  title: string;
  onClick: (e: React.MouseEvent) => void;
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      className="flex h-6 w-6 items-center justify-center rounded-full transition-transform hover:scale-105"
      style={{ backgroundColor: `${tint}1a`, color: tint }}
    >
      <Icon className="h-3 w-3" />
    </button>
  );
}
