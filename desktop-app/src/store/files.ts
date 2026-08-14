import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface FileEntry {
  name: string;
  isDirectory: boolean;
  sizeBytes: number;
  modifiedAt: number;
}

export type ClipboardOp = "cut" | "copy";

export interface FileClipboard {
  path: string;
  name: string;
  operation: ClipboardOp;
}

interface FilesState {
  currentPath: string;
  entries: FileEntry[];
  clipboard: FileClipboard | null;
  viewMode: "grid" | "list";
  uploadingFileName: string | null;
  successMessage: string | null;
  errorMessage: string | null;
  loaded: boolean;
}

export const useFilesStore = create<FilesState>(() => ({
  currentPath: "",
  entries: [],
  clipboard: null,
  viewMode: "grid",
  uploadingFileName: null,
  successMessage: null,
  errorMessage: null,
  loaded: false,
}));

export function setViewMode(mode: "grid" | "list") {
  useFilesStore.setState({ viewMode: mode });
}

export function joinPath(currentPath: string, name: string): string {
  return currentPath ? `${currentPath}/${name}` : name;
}

export function parentPath(currentPath: string): string {
  const idx = currentPath.lastIndexOf("/");
  return idx === -1 ? "" : currentPath.slice(0, idx);
}

export async function listFiles(path: string) {
  await invoke("list_files", { path });
}

export async function downloadFile(path: string) {
  await invoke("download_file", { path });
}

export async function uploadFile(path: string, name: string, dataBase64: string, mimeType: string) {
  await invoke("upload_file", { path, name, dataBase64, mimeType });
}

export async function createFolder(name: string) {
  await invoke("create_folder", { name });
}

export async function renameFile(path: string, newName: string) {
  await invoke("rename_file", { path, newName });
}

export async function deleteFile(path: string) {
  await invoke("delete_file", { path });
}

export async function cutToClipboard(path: string, name: string) {
  await invoke("cut_to_clipboard", { path, name });
  useFilesStore.setState({ clipboard: { path, name, operation: "cut" } });
}

export async function copyToClipboard(path: string, name: string) {
  await invoke("copy_to_clipboard", { path, name });
  useFilesStore.setState({ clipboard: { path, name, operation: "copy" } });
}

export async function pasteClipboard() {
  const clipboard = useFilesStore.getState().clipboard;
  await invoke("paste_clipboard");
  // Cut is a one-shot operation (matches the Rust side clearing it on a successful move) —
  // clear optimistically rather than waiting on a dedicated event for this alone. Copy leaves
  // the clipboard as-is so the same item can be pasted again elsewhere.
  if (clipboard?.operation === "cut") {
    useFilesStore.setState({ clipboard: null });
  }
}

let initialized = false;

export function initFilesListeners() {
  if (initialized) return;
  initialized = true;

  invoke<{ current_path: string; entries: FileEntry[]; clipboard: FileClipboard | null }>(
    "get_files_state",
  ).then((snapshot) => {
    useFilesStore.setState({
      currentPath: snapshot.current_path,
      entries: snapshot.entries,
      clipboard: snapshot.clipboard,
      loaded: true,
    });
  });

  listen<{ path: string; entries: FileEntry[]; error: string | null }>("files-listing", (event) => {
    useFilesStore.setState({
      currentPath: event.payload.path,
      entries: event.payload.entries,
      loaded: true,
    });
    if (event.payload.error) {
      useFilesStore.setState({ errorMessage: event.payload.error });
      setTimeout(() => useFilesStore.setState({ errorMessage: null }), 4000);
    }
  });

  listen<string | null>("files-upload-progress", (event) => {
    useFilesStore.setState({ uploadingFileName: event.payload });
  });

  listen<string>("files-success", (event) => {
    useFilesStore.setState({ successMessage: event.payload });
    setTimeout(() => useFilesStore.setState({ successMessage: null }), 3000);
  });

  listen<string>("files-error", (event) => {
    useFilesStore.setState({ errorMessage: event.payload });
    setTimeout(() => useFilesStore.setState({ errorMessage: null }), 4000);
  });
}
