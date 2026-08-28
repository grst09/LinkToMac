import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface Note {
  id: string;
  title: string;
  body: string;
  createdAt: number;
  updatedAt: number;
  isPinned: boolean;
  /** Raw base64, no `data:image/...;base64,` prefix — see `imageSrc` in NotesView.tsx for that. */
  imageBase64?: string | null;
}

/** A note created/edited on the Mac while Notes sync was off (see `store/local_notes.rs` on the
 *  Rust side) — id always starts with `"local-"`. Pushed to the phone as a brand-new note once
 *  sync is re-enabled, at which point it disappears from `pending` and shows up in `notes`
 *  instead via the next `notes.sync`. */
export interface PendingNote {
  id: string;
  title: string;
  body: string;
  createdAt: number;
  updatedAt: number;
  isPinned: boolean;
  imageBase64?: string | null;
}

interface NotesState {
  notes: Note[];
  pending: PendingNote[];
  loaded: boolean;
  lastError: string | null;
  /** Ids of synced (phone-origin) notes with an edit/pin/delete queued because the phone wasn't
   *  reachable when it was made — see `pending_note_mutations.rs`. Cleared for an id once
   *  `dispatch::sync_settings::reconcile_note_mutations` actually replays it on reconnect. */
  pendingMutationIds: Set<string>;
}

export const useNotesStore = create<NotesState>(() => ({
  notes: [],
  pending: [],
  loaded: false,
  lastError: null,
  pendingMutationIds: new Set(),
}));

export async function refreshNotes() {
  await invoke("refresh_notes");
}

/** These commands reject (e.g. "Notes sync is off", or no phone connected) without ever going
 *  through the `notes-error` event — that event only covers a *phone-reported* failure after a
 *  round trip. Without this, a rejected invoke was an unhandled promise rejection: the button
 *  looked like it did nothing, with no message anywhere. Route it through the same `lastError`
 *  banner the event-based errors use. */
function surfaceError(err: unknown) {
  const message = err instanceof Error ? err.message : String(err);
  useNotesStore.setState({ lastError: message });
  setTimeout(() => useNotesStore.setState({ lastError: null }), 4000);
}

/** Backend decides whether this becomes a real `notes.create` or a local-only pending entry,
 *  based on the current sync toggle — the frontend doesn't need to know in advance. Returns
 *  whether it actually went through, so a caller that's tracking "have I created this draft yet"
 *  (see `NoteEditPanel`) can retry instead of silently losing the note on failure. */
export async function createNote(
  title: string,
  body: string,
  imageBase64?: string | null,
): Promise<boolean> {
  try {
    await invoke("create_note", { title, body, imageBase64: imageBase64 ?? null });
    return true;
  } catch (err) {
    surfaceError(err);
    return false;
  }
}

export async function updateNote(
  id: string,
  title: string,
  body: string,
  imageBase64?: string | null,
): Promise<boolean> {
  try {
    await invoke("update_note", { id, title, body, imageBase64: imageBase64 ?? null });
    return true;
  } catch (err) {
    surfaceError(err);
    return false;
  }
}

export async function deleteNote(id: string): Promise<boolean> {
  try {
    await invoke("delete_note", { id });
    return true;
  } catch (err) {
    surfaceError(err);
    return false;
  }
}

/** Applied optimistically — pinning is a one-field toggle a user expects to see reflect
 *  instantly, and otherwise it's silent until the phone round-trips a full `notes.sync`
 *  (or, worse, silently swallowed if that invoke rejects). Reverted if the call fails; a
 *  successful `notes.sync` overwrites this guess with the real state either way. */
export async function setNotePinned(id: string, isPinned: boolean) {
  const prev = useNotesStore.getState();
  useNotesStore.setState({
    notes: prev.notes.map((n) => (n.id === id ? { ...n, isPinned } : n)),
    pending: prev.pending.map((n) => (n.id === id ? { ...n, isPinned } : n)),
  });
  try {
    await invoke("set_note_pinned", { id, isPinned });
  } catch (err) {
    useNotesStore.setState({ notes: prev.notes, pending: prev.pending });
    surfaceError(err);
  }
}

export function isPendingNote(id: string): boolean {
  return id.startsWith("local-");
}

let initialized = false;

export function initNotesListeners() {
  if (initialized) return;
  initialized = true;

  invoke<Note[]>("list_notes").then((notes) => {
    useNotesStore.setState({ notes, loaded: true });
  });

  invoke<PendingNote[]>("list_local_notes").then((pending) => {
    useNotesStore.setState({ pending });
  });

  invoke<string[]>("list_pending_note_mutation_ids").then((ids) => {
    useNotesStore.setState({ pendingMutationIds: new Set(ids) });
  });

  listen<Note[]>("notes-updated", (event) => {
    useNotesStore.setState({ notes: event.payload, loaded: true });
  });

  listen<PendingNote[]>("local-notes-updated", (event) => {
    useNotesStore.setState({ pending: event.payload });
  });

  listen<string[]>("pending-note-mutations-updated", (event) => {
    useNotesStore.setState({ pendingMutationIds: new Set(event.payload) });
  });

  listen<string>("notes-error", (event) => {
    useNotesStore.setState({ lastError: event.payload });
    setTimeout(() => useNotesStore.setState({ lastError: null }), 4000);
  });
}
