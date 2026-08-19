import { useEffect, useMemo, useState } from "react";
import { motion } from "framer-motion";
import { Plus, Trash2, Pencil, FileText, Search as SearchIcon } from "lucide-react";
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
  useNotesStore,
  type Note,
} from "../store/notes";

export function NotesView() {
  const { notes, loaded, lastError } = useNotesStore();
  const [searchText, setSearchText] = useState("");
  const [listWidth, setListWidth] = useState(300);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [isCreating, setIsCreating] = useState(false);
  const [isEditing, setIsEditing] = useState(false);

  useEffect(() => {
    initNotesListeners();
  }, []);

  const filtered = useMemo(() => {
    if (!searchText.trim()) return notes;
    const q = searchText.toLowerCase();
    return notes.filter((n) => n.title.toLowerCase().includes(q) || n.body.toLowerCase().includes(q));
  }, [notes, searchText]);

  const selected = notes.find((n) => n.id === selectedId) ?? null;

  function selectNote(id: string) {
    setSelectedId(id);
    setIsCreating(false);
    setIsEditing(false);
  }

  return (
    <div className="flex h-full flex-col">
      <SectionHeader
        section={sectionMeta("notes")}
        subtitle={`${notes.length} note${notes.length === 1 ? "" : "s"}`}
        trailing={
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
        }
      />

      {lastError && (
        <div className="mx-4 mt-3 flex items-center gap-2 rounded-lg bg-orange-500/10 px-3 py-2 text-xs text-orange-700 dark:text-orange-400">
          {lastError}
        </div>
      )}

      <div className="flex flex-1 overflow-hidden">
        <div className="flex flex-col" style={{ width: listWidth }}>
          <SearchBar value={searchText} onChange={setSearchText} placeholder="Search notes" />
          <div className="flex-1 overflow-y-auto">
            {!loaded ? null : notes.length === 0 ? (
              <Placeholder icon={FileText} text="No notes yet" />
            ) : filtered.length === 0 ? (
              <Placeholder icon={SearchIcon} text="No matching notes" />
            ) : (
              <ul>
                {filtered.map((note) => (
                  <NoteRow
                    key={note.id}
                    note={note}
                    selected={note.id === selectedId}
                    onClick={() => selectNote(note.id)}
                  />
                ))}
              </ul>
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
            <NoteDetailPanel note={selected} onEdit={() => setIsEditing(true)} onDeleted={() => setSelectedId(null)} />
          ) : (
            <Placeholder icon={FileText} text="Select a note" />
          )}
        </div>
      </div>
    </div>
  );
}

function NoteRow({ note, selected, onClick }: { note: Note; selected: boolean; onClick: () => void }) {
  const snippet = note.body.trim().split("\n")[0] ?? "";
  return (
    <li>
      <button
        onClick={onClick}
        className={`flex w-full flex-col items-start gap-0.5 px-3 py-2 text-left transition-colors ${
          selected ? "bg-blue-500/15" : "hover:bg-black/[0.03] dark:hover:bg-white/[0.05]"
        }`}
      >
        <span className="w-full truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
          {note.title.trim() || "Untitled"}
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
  onEdit,
  onDeleted,
}: {
  note: Note;
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
          <div className="flex shrink-0 gap-1.5">
            <CircleAction icon={Pencil} tint="#3b82f6" onClick={onEdit} title="Edit" />
            <CircleAction icon={Trash2} tint="#ef4444" onClick={() => setConfirmingDelete(true)} title="Delete" />
          </div>
        </div>
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

function NoteEditPanel({ existing, onDone }: { existing: Note | null; onDone: (id?: string) => void }) {
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
          className="rounded-lg bg-blue-500 px-3.5 py-1.5 text-[13px] font-medium text-white disabled:opacity-40 hover:bg-blue-600 transition-colors"
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
