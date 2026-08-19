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
}

interface NotesState {
  notes: Note[];
  pending: PendingNote[];
  loaded: boolean;
  lastError: string | null;
}

export const useNotesStore = create<NotesState>(() => ({
  notes: [],
  pending: [],
  loaded: false,
  lastError: null,
}));

export async function refreshNotes() {
  await invoke("refresh_notes");
}

/** Backend decides whether this becomes a real `notes.create` or a local-only pending entry,
 *  based on the current sync toggle — the frontend doesn't need to know in advance. */
export async function createNote(title: string, body: string) {
  await invoke("create_note", { title, body });
}

export async function updateNote(id: string, title: string, body: string) {
  await invoke("update_note", { id, title, body });
}

export async function deleteNote(id: string) {
  await invoke("delete_note", { id });
}

export async function setNotePinned(id: string, isPinned: boolean) {
  await invoke("set_note_pinned", { id, isPinned });
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

  listen<Note[]>("notes-updated", (event) => {
    useNotesStore.setState({ notes: event.payload, loaded: true });
  });

  listen<PendingNote[]>("local-notes-updated", (event) => {
    useNotesStore.setState({ pending: event.payload });
  });

  listen<string>("notes-error", (event) => {
    useNotesStore.setState({ lastError: event.payload });
    setTimeout(() => useNotesStore.setState({ lastError: null }), 4000);
  });
}
