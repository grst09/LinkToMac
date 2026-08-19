import { useEffect, useMemo, useState } from "react";
import { motion } from "framer-motion";
import { Plus, Trash2, Pencil, FileText, Search as SearchIcon, CloudOff, RefreshCw, Pin, PinOff } from "lucide-react";
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

/** Apple Notes-style date buckets — pinned notes get their own section regardless of date. */
function sectionFor(updatedAt: number): string {
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const startOfYesterday = new Date(startOfToday);
  startOfYesterday.setDate(startOfYesterday.getDate() - 1);
  const start7 = new Date(startOfToday);
  start7.setDate(start7.getDate() - 7);
  const start30 = new Date(startOfToday);
  start30.setDate(start30.getDate() - 30);
  const date = new Date(updatedAt);
  if (date >= startOfToday) return "Today";
  if (date >= startOfYesterday) return "Yesterday";
  if (date >= start7) return "Previous 7 Days";
  if (date >= start30) return "Previous 30 Days";
  return "Earlier";
}

const SECTION_ORDER = ["Pinned", "Today", "Yesterday", "Previous 7 Days", "Previous 30 Days", "Earlier"];

function groupIntoSections(notes: NoteRowItem[]): [string, NoteRowItem[]][] {
  const groups = new Map<string, NoteRowItem[]>();
  for (const note of notes) {
    const key = note.isPinned ? "Pinned" : sectionFor(note.updatedAt);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(note);
  }
  for (const group of groups.values()) {
    group.sort((a, b) => b.updatedAt - a.updatedAt);
  }
  return SECTION_ORDER.filter((key) => groups.has(key)).map((key) => [key, groups.get(key)!]);
}

export function NotesView() {
  const { notes, pending, loaded, lastError } = useNotesStore();
  const notesEnabled = useSyncSettingsStore((s) => s.settings.notesEnabled);
  const [searchText, setSearchText] = useState("");
  const [listWidth, setListWidth] = useState(300);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [isCreating, setIsCreating] = useState(false);
  const [isEditing, setIsEditing] = useState(false);

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

  const filtered = useMemo(() => {
    if (!searchText.trim()) return combined;
    const q = searchText.toLowerCase();
    return combined.filter((n) => n.title.toLowerCase().includes(q) || n.body.toLowerCase().includes(q));
  }, [combined, searchText]);

  const sections = useMemo(() => groupIntoSections(filtered), [filtered]);

  const selected = combined.find((n) => n.id === selectedId) ?? null;

  function selectNote(id: string) {
    setSelectedId(id);
    setIsCreating(false);
    setIsEditing(false);
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
              onClick={() => {
                setIsCreating(true);
                setSelectedId(null);
                setIsEditing(false);
              }}
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
                  <div className="sticky top-0 z-10 bg-white/90 dark:bg-neutral-950/90 backdrop-blur px-3 py-1 text-xs font-semibold text-neutral-400">
                    {label}
                  </div>
                  <ul>
                    {items.map((note) => (
                      <NoteRow
                        key={note.id}
                        note={note}
                        selected={note.id === selectedId}
                        onClick={() => selectNote(note.id)}
                      />
                    ))}
                  </ul>
                </div>
              ))
            )}
          </div>
        </div>

        <ResizableDivider width={listWidth} onWidthChange={setListWidth} minWidth={240} maxWidth={480} />

        <div className="m-3 flex-1 overflow-hidden rounded-2xl bg-black/[0.015] dark:bg-white/[0.02]">
          {isCreating ? (
            <NoteEditPanel
              existing={null}
              onDone={(id) => {
                setIsCreating(false);
                if (id) setSelectedId(id);
              }}
            />
          ) : isEditing && selected ? (
            <NoteEditPanel existing={selected} onDone={() => setIsEditing(false)} />
          ) : selected ? (
            <NoteDetailPanel
              note={selected}
              canEdit={notesEnabled || isPendingNote(selected.id)}
              onEdit={() => setIsEditing(true)}
              onDeleted={() => setSelectedId(null)}
            />
          ) : (
            <Placeholder icon={FileText} text="Select a note" />
          )}
        </div>
      </div>
    </div>
  );
}

function NoteRow({ note, selected, onClick }: { note: NoteRowItem; selected: boolean; onClick: () => void }) {
  const snippet = note.body.trim().split("\n")[0] ?? "";
  return (
    <li>
      <button
        onClick={onClick}
        className={`flex w-full flex-col items-start gap-0.5 px-3 py-2 text-left transition-colors ${
          selected ? "bg-yellow-500/15" : "hover:bg-black/[0.03] dark:hover:bg-white/[0.05]"
        }`}
      >
        <span className="flex w-full items-center gap-1.5">
          {note.isPinned && <Pin className="h-3 w-3 shrink-0 fill-current text-neutral-400" />}
          <span className="truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
            {note.title.trim() || "Untitled"}
          </span>
          {note.pending && (
            <span
              title="Not synced with your Mac's phone yet"
              className="shrink-0 rounded-full bg-orange-500/10 px-1.5 py-0.5 text-[10px] font-medium text-orange-600 dark:text-orange-400"
            >
              Not synced
            </span>
          )}
        </span>
        <span className="w-full truncate text-xs text-neutral-500 dark:text-neutral-400">
          {relativeTime(note.updatedAt)}
          {snippet ? ` · ${snippet}` : ""}
        </span>
      </button>
    </li>
  );
}

function NoteDetailPanel({
  note,
  canEdit,
  onEdit,
  onDeleted,
}: {
  note: NoteRowItem;
  canEdit: boolean;
  onEdit: () => void;
  onDeleted: () => void;
}) {
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  return (
    <div className="flex h-full flex-col overflow-y-auto">
      <motion.div
        key={note.id}
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.2 }}
        className="flex-1 px-6 pb-6 pt-8"
      >
        <div className="flex items-start justify-between gap-3">
          <h2 className="text-xl font-bold text-neutral-900 dark:text-neutral-100">
            {note.title.trim() || "Untitled"}
          </h2>
          {canEdit && (
            <div className="flex shrink-0 gap-1.5">
              <CircleAction
                icon={note.isPinned ? PinOff : Pin}
                tint="#eab308"
                onClick={() => setNotePinned(note.id, !note.isPinned)}
                title={note.isPinned ? "Unpin" : "Pin"}
              />
              <CircleAction icon={Pencil} tint="#eab308" onClick={onEdit} title="Edit" />
              <CircleAction icon={Trash2} tint="#ef4444" onClick={() => setConfirmingDelete(true)} title="Delete" />
            </div>
          )}
        </div>
        {note.pending && (
          <p className="mt-2 flex items-center gap-1.5 text-xs text-orange-600 dark:text-orange-400">
            <CloudOff className="h-3 w-3 shrink-0" />
            Not synced with your phone yet — will sync once Notes sync is back on.
          </p>
        )}
        <p className="mt-1 text-xs text-neutral-400 dark:text-neutral-500">
          Last edited {relativeTime(note.updatedAt)}
        </p>
        <p className="mt-4 whitespace-pre-wrap text-[14px] leading-relaxed text-neutral-800 dark:text-neutral-200">
          {note.body}
        </p>
      </motion.div>

      {confirmingDelete && (
        <ConfirmDialog
          title={`Delete "${note.title.trim() || "Untitled"}"?`}
          message="This can't be undone."
          onCancel={() => setConfirmingDelete(false)}
          onConfirm={async () => {
            await deleteNote(note.id);
            setConfirmingDelete(false);
            onDeleted();
          }}
        />
      )}
    </div>
  );
}

function NoteEditPanel({ existing, onDone }: { existing: NoteRowItem | null; onDone: (id?: string) => void }) {
  const [title, setTitle] = useState(existing?.title ?? "");
  const [body, setBody] = useState(existing?.body ?? "");

  const canSave = title.trim() !== "" || body.trim() !== "";

  async function save() {
    if (!canSave) return;
    if (existing) {
      await updateNote(existing.id, title.trim(), body.trim());
      onDone();
    } else {
      await createNote(title.trim(), body.trim());
      onDone();
    }
  }

  return (
    <div className="flex h-full flex-col gap-3 px-6 pb-6 pt-8">
      <input
        autoFocus
        value={title}
        onChange={(e) => setTitle(e.target.value)}
        placeholder="Title"
        className="w-full bg-transparent text-xl font-bold text-neutral-900 dark:text-neutral-100 placeholder:text-neutral-400 focus:outline-none"
      />
      <textarea
        value={body}
        onChange={(e) => setBody(e.target.value)}
        placeholder="Start typing…"
        className="flex-1 w-full resize-none bg-transparent text-[14px] leading-relaxed text-neutral-800 dark:text-neutral-200 placeholder:text-neutral-400 focus:outline-none"
      />
      <div className="flex justify-end gap-2">
        <button
          onClick={() => onDone()}
          className="rounded-lg px-3.5 py-1.5 text-[13px] text-neutral-600 dark:text-neutral-300 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
        >
          Cancel
        </button>
        <button
          onClick={save}
          disabled={!canSave}
          className="rounded-lg bg-yellow-500 px-3.5 py-1.5 text-[13px] font-medium text-white disabled:opacity-40 hover:bg-yellow-600 transition-colors"
        >
          {existing ? "Save" : "Create"}
        </button>
      </div>
    </div>
  );
}

function CircleAction({
  icon: Icon,
  tint,
  onClick,
  title,
}: {
  icon: typeof Pencil;
  tint: string;
  onClick: () => void;
  title: string;
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      className="flex h-8 w-8 items-center justify-center rounded-full transition-transform hover:scale-105"
      style={{ backgroundColor: `${tint}1a`, color: tint }}
    >
      <Icon className="h-3.5 w-3.5" />
    </button>
  );
}
