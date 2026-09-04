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

export interface FilePreviewData {
  path: string;
  mimeType: string | null;
  dataBase64: string;
}

export type FilesViewMode = "grid" | "list" | "preview";

interface FilesState {
  currentPath: string;
  entries: FileEntry[];
  clipboard: FileClipboard | null;
  viewMode: FilesViewMode;
  uploadingFileName: string | null;
  successMessage: string | null;
  errorMessage: string | null;
  loaded: boolean;
  previewData: FilePreviewData | null;
  previewLoading: boolean;
}

export const useFilesStore = create<FilesState>(() => ({
  currentPath: "",
  entries: [],
  clipboard: null,
  viewMode: "list",
  uploadingFileName: null,
  successMessage: null,
  errorMessage: null,
  loaded: false,
  previewData: null,
  previewLoading: false,
}));

function showTransientError(message: string) {
  useFilesStore.setState({ errorMessage: message, previewLoading: false });
  setTimeout(() => useFilesStore.setState({ errorMessage: null }), 4000);
}

/** Every files.* command below goes through this instead of calling `invoke` directly. A
 *  Tauri command can reject before ever reaching the phone (most commonly "no active
 *  connection") — without this, that rejection was an unhandled promise that vanished with
 *  zero user-facing feedback, so an action like Back could silently do nothing and leave no
 *  trace of why. */
async function invokeFiles<T>(command: string, args?: Record<string, unknown>): Promise<T | undefined> {
  try {
    return await invoke<T>(command, args);
  } catch (e) {
    showTransientError(typeof e === "string" ? e : "Something went wrong — check the connection");
    return undefined;
  }
}

export function setViewMode(mode: FilesViewMode) {
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
  await invokeFiles("list_files", { path });
}

/** `open: true` opens the file with the OS default app once downloaded (double-click);
 *  `open: false` downloads it and reveals it in Finder (the "Download" context-menu item). */
export async function downloadFile(path: string, open: boolean) {
  await invokeFiles("download_file", { path, open });
}

/** Fetches a file's bytes for the preview panel — result arrives via the "files-preview" event,
 *  not this call's return value (matches every other files.* command in this file). */
export async function previewFile(path: string) {
  useFilesStore.setState({ previewLoading: true });
  await invokeFiles("preview_file", { path });
}

export function clearPreview() {
  useFilesStore.setState({ previewData: null, previewLoading: false });
}

export async function uploadFile(path: string, name: string, dataBase64: string, mimeType: string) {
  await invokeFiles("upload_file", { path, name, dataBase64, mimeType });
}

export async function createFolder(name: string) {
  await invokeFiles("create_folder", { name });
}

export async function renameFile(path: string, newName: string) {
  await invokeFiles("rename_file", { path, newName });
}

export async function deleteFile(path: string) {
  await invokeFiles("delete_file", { path });
}

export async function cutToClipboard(path: string, name: string) {
  await invokeFiles("cut_to_clipboard", { path, name });
  useFilesStore.setState({ clipboard: { path, name, operation: "cut" } });
}

export async function copyToClipboard(path: string, name: string) {
  await invokeFiles("copy_to_clipboard", { path, name });
  useFilesStore.setState({ clipboard: { path, name, operation: "copy" } });
}

export async function pasteClipboard() {
  const clipboard = useFilesStore.getState().clipboard;
  await invokeFiles("paste_clipboard");
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
      previewData: null,
      previewLoading: false,
    });
    if (event.payload.error) {
      showTransientError(event.payload.error);
    }
  });

  listen<{ path: string; name: string; mimeType: string | null; dataBase64: string }>(
    "files-preview",
    (event) => {
      useFilesStore.setState({
        previewData: {
          path: event.payload.path,
          mimeType: event.payload.mimeType,
          dataBase64: event.payload.dataBase64,
        },
        previewLoading: false,
      });
    },
  );

  listen<string | null>("files-upload-progress", (event) => {
    useFilesStore.setState({ uploadingFileName: event.payload });
  });

  listen<string>("files-success", (event) => {
    useFilesStore.setState({ successMessage: event.payload });
    setTimeout(() => useFilesStore.setState({ successMessage: null }), 3000);
  });

  listen<string>("files-error", (event) => {
    showTransientError(event.payload);
  });
}
