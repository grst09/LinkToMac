import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { motion } from "framer-motion";
import {
  Plus,
  Trash2,
  FileText,
  Search as SearchIcon,
  CloudOff,
  RefreshCw,
  Pin,
  PinOff,
  ChevronUp,
  ChevronDown,
  X,
  Check,
  ImagePlus,
} from "lucide-react";
import { SectionHeader } from "./SectionHeader";
import { SearchBar } from "./SearchBar";
import { ResizableDivider } from "./ResizableDivider";
import { ConfirmDialog, Placeholder } from "./ContactDetail";
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

/** Images live inline in the body as standard markdown image tokens (`![](data:...)`) instead of
 *  a separate field — see `NoteEditPanel` for why (a plain `<textarea>` can't render a picture
 *  inline, so the body gets parsed into alternating text/image "blocks" for editing and
 *  re-serialized back into one string, image tokens included, on every autosave). This keeps the
 *  wire format (`body: String`, unchanged on both Rust and Kotlin) and the offline-edit queue
 *  (`store/pending_note_mutations.rs`) completely unaware images exist at all. */
const IMAGE_TOKEN_RE = /!\[\]\((data:image\/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+)\)/g;
const IMAGE_TOKEN_RE_ONCE = /!\[\]\((data:image\/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+)\)/;

interface TextBlock {
  type: "text";
  id: string;
  text: string;
}
interface ImageBlock {
  type: "image";
  id: string;
  dataUrl: string;
}
type NoteBlock = TextBlock | ImageBlock;

let blockIdCounter = 0;
function newBlockId(): string {
  blockIdCounter += 1;
  return `blk${blockIdCounter}`;
}

function parseBlocks(body: string): NoteBlock[] {
  const blocks: NoteBlock[] = [];
  let lastIndex = 0;
  for (const match of body.matchAll(IMAGE_TOKEN_RE)) {
    const idx = match.index ?? 0;
    if (idx > lastIndex) blocks.push({ type: "text", id: newBlockId(), text: body.slice(lastIndex, idx) });
    blocks.push({ type: "image", id: newBlockId(), dataUrl: match[1] });
    lastIndex = idx + match[0].length;
  }
  if (lastIndex < body.length || blocks.length === 0) {
    blocks.push({ type: "text", id: newBlockId(), text: body.slice(lastIndex) });
  }
  return normalizeBlocks(blocks);
}

/** Merges adjacent text blocks (e.g. after an image is removed) and guarantees there's always at
 *  least one text block to type into, even in a note that's entirely images. */
function normalizeBlocks(blocks: NoteBlock[]): NoteBlock[] {
  const merged: NoteBlock[] = [];
  for (const block of blocks) {
    const prev = merged[merged.length - 1];
    if (block.type === "text" && prev?.type === "text") {
      prev.text += block.text;
    } else {
      merged.push({ ...block });
    }
  }
  if (merged.length === 0 || merged[merged.length - 1].type === "image") {
    merged.push({ type: "text", id: newBlockId(), text: "" });
  }
  return merged;
}

function serializeBlocks(blocks: NoteBlock[]): string {
  return blocks.map((b) => (b.type === "text" ? b.text : `![](${b.dataUrl})`)).join("");
}

/** A legacy note's single cover image (this session's previous feature, now replaced by inline
 *  images) shows up as a leading inline image the first time the note is opened — the very next
 *  autosave folds it into `body` as a token and the field stops being written, so this only ever
 *  matters for notes that predate inline images. */
function initialBlocksFor(body: string, legacyImage: string | null): NoteBlock[] {
  const blocks = parseBlocks(body);
  if (!legacyImage) return blocks;
  return normalizeBlocks([{ type: "image", id: newBlockId(), dataUrl: imageSrc(legacyImage) }, ...blocks]);
}

interface PreviewLine {
  text: string;
  /** `undefined` = a plain text line; `true`/`false` = a checklist item and whether it's done. */
  checked?: boolean;
}

/** Recognizes `- [ ] task` / `- [x] task` markdown-style checklist lines, and strips inline image
 *  tokens out of the displayed text — for the card preview only; the editor itself renders images
 *  as real inline thumbnails (see `NoteEditPanel`). */
function parsePreviewLines(body: string, maxLines: number): PreviewLine[] {
  const checklistPattern = /^-\s*\[([ xX])\]\s*(.*)$/;
  const withoutImages = body.replace(IMAGE_TOKEN_RE, "").trim();
  const lines: PreviewLine[] = [];
  for (const raw of withoutImages.split("\n")) {
    const line = raw.trim();
    if (!line) continue;
    if (lines.length >= maxLines) break;
    const match = checklistPattern.exec(line);
    if (match) {
      lines.push({ text: match[2] || "(empty)", checked: match[1].toLowerCase() === "x" });
    } else {
      lines.push({ text: line });
    }
  }
  return lines;
}

/** The card grid's cover thumbnail: the first inline image found anywhere in the body, falling
 *  back to a legacy note's `imageBase64` field if it hasn't been opened (and thus migrated to an
 *  inline token) yet. */
function extractCoverImage(body: string, legacyImage: string | null | undefined): string | null {
  const match = IMAGE_TOKEN_RE_ONCE.exec(body);
  if (match) return match[1];
  return legacyImage ? imageSrc(legacyImage) : null;
}

/** A match's position is local to the single text block it was found in — image blocks aren't
 *  searchable, and a query never matches across a block boundary (a picture splitting one search
 *  across two blocks is treated as two separate non-matches, which is the right call: there's no
 *  sensible way to "replace" text that has an image in the middle of it). */
interface Match {
  blockId: string;
  start: number;
  end: number;
}

/** Plain case-insensitive substring search, non-overlapping — good enough for find/replace in a
 *  note body and avoids ever building a RegExp out of user-typed (unescaped) text. */
function findMatchesInText(text: string, query: string): { start: number; end: number }[] {
  if (!query) return [];
  const haystack = text.toLowerCase();
  const needle = query.toLowerCase();
  const matches: { start: number; end: number }[] = [];
  let from = 0;
  while (from <= haystack.length - needle.length) {
    const idx = haystack.indexOf(needle, from);
    if (idx === -1) break;
    matches.push({ start: idx, end: idx + needle.length });
    from = idx + needle.length;
  }
  return matches;
}

function findMatches(blocks: NoteBlock[], query: string): Match[] {
  if (!query) return [];
  const matches: Match[] = [];
  for (const block of blocks) {
    if (block.type !== "text") continue;
    for (const m of findMatchesInText(block.text, query)) {
      matches.push({ blockId: block.id, ...m });
    }
  }
  return matches;
}

export function NotesView() {
  const { notes, pending, loaded, lastError } = useNotesStore();
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

  const combined: NoteRowItem[] = useMemo(() => {
    const items = [
      ...pending.map((n) => ({ ...n, pending: true })),
      ...notes.map((n) => ({ ...n, pending: false })),
    ];
    return items.sort((a, b) => b.updatedAt - a.updatedAt);
  }, [notes, pending]);

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
              <Placeholder icon={FileText} text="No notes yet" />
            ) : filtered.length === 0 ? (
              <Placeholder icon={SearchIcon} text="No matching notes" />
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
              pending={false}
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
              pending={selected.pending}
              canEdit={canEdit(selected, notesEnabled)}
              updatedAt={selected.updatedAt}
            />
          ) : (
            <Placeholder icon={FileText} text="Select a note" />
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
  const previewLines = useMemo(() => parsePreviewLines(note.body, 4), [note.body]);
  const coverImage = useMemo(() => extractCoverImage(note.body, note.imageBase64), [note.body, note.imageBase64]);

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
            {note.pending && (
              <span
                title="Not synced with your Mac's phone yet"
                className="shrink-0 rounded-full bg-orange-500/10 px-1.5 py-0.5 font-medium text-orange-600 dark:text-orange-400"
              >
                Not synced
              </span>
            )}
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
 *  The body is edited as a sequence of "blocks" — text runs and inline images — rather than one
 *  plain string, since a `<textarea>` can't render a picture inline. Each text block is its own
 *  auto-growing textarea (see `NoteTextBlock`); images sit between them as real `<img>` elements.
 *  Inserting an image (via the toolbar button, a paste, or a drop) splits whichever text block
 *  currently has focus at its caret and splices the image in between the two halves. On every
 *  autosave the blocks are re-joined into one string (images re-emitted as `![](data:...)`
 *  tokens — see `serializeBlocks`/`parseBlocks` above). */
function NoteEditPanel({
  noteId,
  isDraft,
  initialTitle,
  initialBody,
  initialImage,
  pending,
  canEdit,
  updatedAt,
}: {
  noteId: string | null;
  isDraft: boolean;
  initialTitle: string;
  initialBody: string;
  initialImage: string | null;
  pending: boolean;
  canEdit: boolean;
  updatedAt: number | null;
}) {
  const [title, setTitle] = useState(initialTitle);
  const [blocks, setBlocks] = useState<NoteBlock[]>(() => initialBlocksFor(initialBody, initialImage));
  const [imageDragOver, setImageDragOver] = useState(false);
  const [imageError, setImageError] = useState<string | null>(null);
  const [pendingFocusBlockId, setPendingFocusBlockId] = useState<string | null>(null);

  const blockRefs = useRef<Record<string, HTMLTextAreaElement | null>>({});
  const lastFocusedBlockIdRef = useRef<string | null>(null);

  const dirtyRef = useRef(false);
  const createdRef = useRef(false);
  const latestRef = useRef({ title, blocks, isDraft, noteId });
  latestRef.current = { title, blocks, isDraft, noteId };

  function flush() {
    const { title, blocks, isDraft, noteId } = latestRef.current;
    const t = title.trim();
    const b = serializeBlocks(blocks).trim();
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

  /** Splits the target block's text at its current caret and splices a new image block between
   *  the two halves, focusing the new trailing half. `blockId` is the block to target (the one
   *  a paste happened in, or the last-focused one for a toolbar-button/drop insert) — falls back
   *  to the end of the last text block if it isn't a real, current text block (e.g. nothing's
   *  been focused yet in a brand-new note). */
  function insertImageAt(dataUrl: string, blockId: string | null) {
    // Reads `blocks` directly from this render's closure rather than via a functional `setBlocks`
    // updater — deliberately, since calling `setPendingFocusBlockId` as a side effect from inside
    // an updater would violate React's purity contract for updaters (they can run more than once
    // per commit). A plain closure read is safe here: this only ever fires once per discrete user
    // action (a paste, a drop, a button click), so there's no risk of it seeing stale state.
    let targetIdx = blockId ? blocks.findIndex((b) => b.id === blockId && b.type === "text") : -1;
    if (targetIdx < 0) {
      for (let i = blocks.length - 1; i >= 0; i--) {
        if (blocks[i].type === "text") {
          targetIdx = i;
          break;
        }
      }
    }
    if (targetIdx < 0) return; // unreachable — normalizeBlocks guarantees a trailing text block
    const target = blocks[targetIdx] as TextBlock;
    const el = blockId ? blockRefs.current[blockId] : null;
    const caret = el && target.id === blockId ? (el.selectionStart ?? target.text.length) : target.text.length;
    const before = target.text.slice(0, caret);
    const after = target.text.slice(caret);
    const afterBlock: TextBlock = { type: "text", id: newBlockId(), text: after };
    const next: NoteBlock[] = [
      ...blocks.slice(0, targetIdx),
      { ...target, text: before },
      { type: "image", id: newBlockId(), dataUrl },
      afterBlock,
      ...blocks.slice(targetIdx + 1),
    ];
    setBlocks(normalizeBlocks(next));
    setPendingFocusBlockId(afterBlock.id);
    dirtyRef.current = true;
  }

  // Focus + place the caret at the start of the freshly-inserted trailing text block, once it's
  // actually mounted (normalizeBlocks can merge it into a neighbor, so it may not exist by id —
  // in that case there's simply nothing to focus and the next render's ref lookup no-ops).
  useEffect(() => {
    if (!pendingFocusBlockId) return;
    const el = blockRefs.current[pendingFocusBlockId];
    if (el) {
      el.focus();
      el.setSelectionRange(0, 0);
    }
    setPendingFocusBlockId(null);
  }, [pendingFocusBlockId, blocks]);

  async function insertImageFile(file: File | null | undefined, blockId: string | null) {
    if (!file) return;
    if (!file.type.startsWith("image/")) {
      setImageError("That's not an image file");
      setTimeout(() => setImageError(null), 3000);
      return;
    }
    try {
      const base64 = await compressImageFile(file);
      insertImageAt(imageSrc(base64), blockId);
    } catch {
      setImageError("Couldn't read that image");
      setTimeout(() => setImageError(null), 3000);
    }
  }

  function updateTextBlock(blockId: string, text: string) {
    setBlocks((prev) => prev.map((b) => (b.id === blockId && b.type === "text" ? { ...b, text } : b)));
    dirtyRef.current = true;
  }

  function removeImageBlock(blockId: string) {
    setBlocks((prev) => normalizeBlocks(prev.filter((b) => b.id !== blockId)));
    dirtyRef.current = true;
  }

  useEffect(() => {
    if (!dirtyRef.current) return;
    const timer = setTimeout(flush, AUTOSAVE_DELAY_MS);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title, blocks, isDraft, noteId]);

  // Flush on unmount too, so switching notes right after typing doesn't drop the last edit.
  useEffect(() => {
    return () => {
      if (dirtyRef.current) flush();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // --- Find & replace, scoped to this note's body -------------------------------------------
  const [findOpen, setFindOpen] = useState(false);
  const [findQuery, setFindQuery] = useState("");
  const [replaceQuery, setReplaceQuery] = useState("");
  const [matchIndex, setMatchIndex] = useState(0);

  const findInputRef = useRef<HTMLInputElement>(null);
  const activeMarkRef = useRef<HTMLElement>(null);

  const matches = useMemo(() => findMatches(blocks, findQuery), [blocks, findQuery]);
  const showHighlights = findOpen && matches.length > 0;
  const activeMatch = matches[matchIndex];

  useEffect(() => setMatchIndex(0), [findQuery]);
  useEffect(() => {
    setMatchIndex((i) => Math.min(i, Math.max(0, matches.length - 1)));
  }, [matches.length]);

  useEffect(() => {
    if (!findOpen) return;
    findInputRef.current?.focus();
    findInputRef.current?.select();
  }, [findOpen]);

  // Keep the active match in view. Each text block now auto-grows to fit its own content instead
  // of scrolling internally — the panel itself is the one scroll container — so a plain
  // scrollIntoView on the active <mark> replaces the old manual offsetTop math that used to sync
  // one shared textarea's scroll position against its overlay.
  useEffect(() => {
    activeMarkRef.current?.scrollIntoView({ block: "center" });
  }, [matchIndex, matches, showHighlights]);

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
    if (!canEdit || matches.length === 0) return;
    const m = matches[matchIndex];
    setBlocks((prev) =>
      prev.map((b) =>
        b.type === "text" && b.id === m.blockId
          ? { ...b, text: b.text.slice(0, m.start) + replaceQuery + b.text.slice(m.end) }
          : b,
      ),
    );
    dirtyRef.current = true;
  }

  function replaceAll() {
    if (!canEdit || matches.length === 0) return;
    const byBlock = new Map<string, Match[]>();
    for (const m of matches) {
      const list = byBlock.get(m.blockId);
      if (list) list.push(m);
      else byBlock.set(m.blockId, [m]);
    }
    setBlocks((prev) =>
      prev.map((b) => {
        if (b.type !== "text") return b;
        const blockMatches = byBlock.get(b.id);
        if (!blockMatches) return b;
        let result = "";
        let cursor = 0;
        for (const m of blockMatches) {
          result += b.text.slice(cursor, m.start) + replaceQuery;
          cursor = m.end;
        }
        result += b.text.slice(cursor);
        return { ...b, text: result };
      }),
    );
    dirtyRef.current = true;
    setMatchIndex(0);
  }

  return (
    <div className="flex h-full flex-col overflow-y-auto">
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
          if (canEdit) insertImageFile(e.dataTransfer.files?.[0], lastFocusedBlockIdRef.current);
        }}
        className={`relative flex flex-1 flex-col gap-3 px-6 pb-6 pt-8 ${
          imageDragOver ? "ring-2 ring-inset ring-yellow-500/50" : ""
        }`}
      >
        {pending && (
          <p className="flex items-center gap-1.5 text-xs text-orange-600 dark:text-orange-400">
            <CloudOff className="h-3 w-3 shrink-0" />
            Not synced with your phone yet — will sync once Notes sync is back on.
          </p>
        )}

        {imageError && <p className="text-xs text-red-500 dark:text-red-400">{imageError}</p>}

        <div className="-mt-1 flex items-center justify-between">
          <p className="text-xs text-neutral-400 dark:text-neutral-500">
            {updatedAt != null ? `Last edited ${relativeTime(updatedAt)}` : ""}
          </p>
          <div className="flex items-center gap-0.5">
            {canEdit && (
              <ImagePicker onPick={(file) => insertImageFile(file, lastFocusedBlockIdRef.current)} />
            )}
            <button
              onClick={() => setFindOpen(true)}
              title="Find & Replace (⌘F)"
              className="rounded-md p-1 text-neutral-400 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
            >
              <SearchIcon className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>

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

        <div className="flex flex-1 flex-col gap-2">
          {blocks.map((block) =>
            block.type === "image" ? (
              <div key={block.id} className="group/image relative shrink-0 overflow-hidden rounded-lg">
                <img src={block.dataUrl} alt="" className="max-h-72 w-full object-cover" />
                {canEdit && (
                  <button
                    onClick={() => removeImageBlock(block.id)}
                    title="Remove image"
                    className="absolute right-2 top-2 flex h-7 w-7 items-center justify-center rounded-full bg-black/50 text-white opacity-0 transition-opacity hover:bg-black/70 group-hover/image:opacity-100"
                  >
                    <X className="h-4 w-4" />
                  </button>
                )}
              </div>
            ) : (
              <NoteTextBlock
                key={block.id}
                block={block}
                canEdit={canEdit}
                matches={matches}
                activeMatch={activeMatch}
                showHighlights={showHighlights}
                activeMarkRef={activeMarkRef}
                registerRef={(el) => {
                  blockRefs.current[block.id] = el;
                }}
                onFocusBlock={() => {
                  lastFocusedBlockIdRef.current = block.id;
                }}
                onChangeText={(text) => updateTextBlock(block.id, text)}
                onPasteImage={(file) => insertImageFile(file, block.id)}
              />
            ),
          )}
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
      </motion.div>
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
      <button
        onClick={() => fileInputRef.current?.click()}
        title="Insert image"
        className="rounded-md p-1 text-neutral-400 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
      >
        <ImagePlus className="h-3.5 w-3.5" />
      </button>
    </>
  );
}

/** One text block of the body — an auto-growing `<textarea>` (no internal scrolling; the whole
 *  panel scrolls instead) with its own highlight overlay for whatever find/replace matches fall
 *  within it. Reused for every text run between images. */
function NoteTextBlock({
  block,
  canEdit,
  matches,
  activeMatch,
  showHighlights,
  activeMarkRef,
  registerRef,
  onFocusBlock,
  onChangeText,
  onPasteImage,
}: {
  block: TextBlock;
  canEdit: boolean;
  matches: Match[];
  activeMatch: Match | undefined;
  showHighlights: boolean;
  activeMarkRef: React.RefObject<HTMLElement | null>;
  registerRef: (el: HTMLTextAreaElement | null) => void;
  onFocusBlock: () => void;
  onChangeText: (text: string) => void;
  onPasteImage: (file: File | null | undefined) => void;
}) {
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const localMatches = useMemo(() => matches.filter((m) => m.blockId === block.id), [matches, block.id]);
  const blockShowsHighlights = showHighlights && localMatches.length > 0;

  // Grows to fit content on every change — typing, a Replace/Replace All, or an image being
  // spliced in/out next to it (which changes this block's text via the split/merge).
  useLayoutEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${el.scrollHeight}px`;
  }, [block.text]);

  return (
    <div className="relative">
      {blockShowsHighlights && (
        <div
          aria-hidden
          className="pointer-events-none absolute inset-0 overflow-hidden whitespace-pre-wrap break-words p-0 text-[14px] leading-relaxed text-neutral-800 dark:text-neutral-200"
        >
          {renderHighlighted(block.text, localMatches, activeMatch, activeMarkRef)}
        </div>
      )}
      <textarea
        ref={(el) => {
          textareaRef.current = el;
          registerRef(el);
        }}
        value={block.text}
        disabled={!canEdit}
        rows={1}
        onFocus={onFocusBlock}
        onChange={(e) => onChangeText(e.target.value)}
        onPaste={(e) => {
          if (!canEdit) return;
          const imageItem = Array.from(e.clipboardData?.items ?? []).find((item) =>
            item.type.startsWith("image/"),
          );
          const file = imageItem?.getAsFile();
          if (file) {
            e.preventDefault();
            onPasteImage(file);
          }
        }}
        placeholder="Start typing…"
        className={`block w-full resize-none overflow-hidden bg-transparent p-0 text-[14px] leading-relaxed placeholder:text-neutral-400 focus:outline-none disabled:opacity-60 ${
          blockShowsHighlights ? "text-transparent caret-neutral-800 dark:caret-neutral-200" : "text-neutral-800 dark:text-neutral-200"
        }`}
      />
    </div>
  );
}

function renderHighlighted(
  text: string,
  matches: Match[],
  activeMatch: Match | undefined,
  activeRef: React.RefObject<HTMLElement | null>,
) {
  const nodes: React.ReactNode[] = [];
  let cursor = 0;
  matches.forEach((m, i) => {
    if (m.start > cursor) nodes.push(text.slice(cursor, m.start));
    const isActive = m === activeMatch;
    nodes.push(
      <mark
        key={i}
        ref={isActive ? activeRef : undefined}
        className={
          isActive
            ? "rounded-[2px] bg-red-500 px-0.5 text-white"
            : "rounded-[2px] bg-transparent text-inherit underline decoration-red-500 decoration-2 underline-offset-2"
        }
      >
        {text.slice(m.start, m.end)}
      </mark>,
    );
    cursor = m.end;
  });
  if (cursor < text.length) nodes.push(text.slice(cursor));
  // A trailing newline at the very end of a <textarea>'s value doesn't visually add a blank
  // line in the browser's own rendering unless there's something after it — mirror that with a
  // trailing space so the overlay's height matches the textarea's exactly.
  nodes.push(" ");
  return nodes;
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
    <div className="absolute inset-x-0 bottom-0 z-20 flex flex-wrap items-center gap-2 rounded-xl border border-black/10 dark:border-white/10 bg-white/95 dark:bg-neutral-800/95 backdrop-blur px-3 py-2 shadow-modal">
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
