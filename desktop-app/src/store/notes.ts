import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface Note {
  id: string;
  title: string;
  body: string;
  createdAt: number;
  updatedAt: number;
}

interface NotesState {
  notes: Note[];
  loaded: boolean;
  lastError: string | null;
}

export const useNotesStore = create<NotesState>(() => ({
  notes: [],
  loaded: false,
  lastError: null,
}));

export async function refreshNotes() {
  await invoke("refresh_notes");
}

export async function createNote(title: string, body: string) {
  await invoke("create_note", { title, body });
}

export async function updateNote(id: string, title: string, body: string) {
  await invoke("update_note", { id, title, body });
}

export async function deleteNote(id: string) {
  await invoke("delete_note", { id });
}

let initialized = false;

export function initNotesListeners() {
  if (initialized) return;
  initialized = true;

  invoke<Note[]>("list_notes").then((notes) => {
    useNotesStore.setState({ notes, loaded: true });
  });

  listen<Note[]>("notes-updated", (event) => {
    useNotesStore.setState({ notes: event.payload, loaded: true });
  });

  listen<string>("notes-error", (event) => {
    useNotesStore.setState({ lastError: event.payload });
    setTimeout(() => useNotesStore.setState({ lastError: null }), 4000);
  });
}
