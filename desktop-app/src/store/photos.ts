import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface PhotoThumbnail {
  id: string;
  takenAt: number;
  thumbnailBase64: string;
}

interface FullImage {
  dataBase64: string;
  mimeType: string;
}

interface PhotosState {
  photos: PhotoThumbnail[];
  hasMore: boolean;
  isLoadingMore: boolean;
  loaded: boolean;
  fullImages: Record<string, FullImage>;
}

export const usePhotosStore = create<PhotosState>(() => ({
  photos: [],
  hasMore: true,
  isLoadingMore: false,
  loaded: false,
  fullImages: {},
}));

/** Same reset-and-re-page-from-0 the phone's own library-changed push already triggers
 *  automatically — this just lets the user ask for it directly instead of waiting. The backend
 *  emits `photos-reset` (already handled below) before the fresh page comes back. */
export async function refreshPhotos() {
  await invoke("refresh_photos");
}

export async function loadNextPage() {
  if (usePhotosStore.getState().isLoadingMore) return;
  usePhotosStore.setState({ isLoadingMore: true });
  try {
    await invoke("request_photo_page");
  } catch {
    // Rust-side already in flight or not connected — clear the optimistic flag we just set.
    usePhotosStore.setState({ isLoadingMore: false });
  }
}

/** No response event to wait on — see `PhotoDeleteRequestPayload`'s doc comment on the Rust
 *  side. Optimistically drops the ids from the grid right away; if the user cancels the phone's
 *  system consent dialog for some or all of them, the next `photos-reset`/`photos-appended`
 *  cycle (driven by the phone's own content-observer-triggered `libraryChanged`) brings them
 *  back — self-correcting rather than needing a real ack. */
export async function deletePhotos(ids: string[]) {
  usePhotosStore.setState((s) => ({
    photos: s.photos.filter((p) => !ids.includes(p.id)),
  }));
  await invoke("delete_photos", { ids });
}

export async function requestPhotoFull(id: string) {
  const cached = usePhotosStore.getState().fullImages[id];
  if (cached) return;
  const existing = await invoke<[string, string] | null>("get_photo_full", { id });
  if (existing) {
    usePhotosStore.setState((s) => ({
      fullImages: { ...s.fullImages, [id]: { dataBase64: existing[0], mimeType: existing[1] } },
    }));
    return;
  }
  await invoke("request_photo_full", { id });
}

let initialized = false;

export function initPhotosListeners() {
  if (initialized) return;
  initialized = true;

  invoke<{ photos: PhotoThumbnail[]; has_more: boolean }>("list_photos").then((snapshot) => {
    usePhotosStore.setState({ photos: snapshot.photos, hasMore: snapshot.has_more, loaded: true });
  });

  listen<{ photos: PhotoThumbnail[]; has_more: boolean }>("photos-appended", (event) => {
    usePhotosStore.setState((s) => ({
      photos: [...s.photos, ...event.payload.photos],
      hasMore: event.payload.has_more,
      isLoadingMore: false,
      loaded: true,
    }));
  });

  listen("photos-reset", () => {
    usePhotosStore.setState({ photos: [], hasMore: true, isLoadingMore: false, fullImages: {} });
  });

  listen<{ id: string; dataBase64: string; mimeType: string }>("photo-full", (event) => {
    usePhotosStore.setState((s) => ({
      fullImages: {
        ...s.fullImages,
        [event.payload.id]: { dataBase64: event.payload.dataBase64, mimeType: event.payload.mimeType },
      },
    }));
  });
}
