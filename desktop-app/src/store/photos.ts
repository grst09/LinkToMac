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
