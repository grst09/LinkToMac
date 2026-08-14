import { useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { Image as ImageIcon, Loader2, X } from "lucide-react";
import { SectionHeader } from "./SectionHeader";
import { sectionMeta } from "../theme/sections";
import {
  initPhotosListeners,
  loadNextPage,
  requestPhotoFull,
  usePhotosStore,
  type PhotoThumbnail,
} from "../store/photos";

const monthFormatter = new Intl.DateTimeFormat(undefined, { month: "long", year: "numeric" });

/** Groups by "Month Year", preserving first-seen order — since `photos` already arrives
 *  newest-first from the server, this naturally produces reverse-chronological month sections
 *  with no extra sort. Ported from PhotosView.swift's `groupedPhotos`. */
function groupByMonth(photos: PhotoThumbnail[]): [string, PhotoThumbnail[]][] {
  const order: string[] = [];
  const groups = new Map<string, PhotoThumbnail[]>();
  for (const photo of photos) {
    const key = monthFormatter.format(new Date(photo.takenAt));
    if (!groups.has(key)) {
      groups.set(key, []);
      order.push(key);
    }
    groups.get(key)!.push(photo);
  }
  return order.map((key) => [key, groups.get(key)!]);
}

export function PhotosView() {
  const { photos, hasMore, isLoadingMore, loaded } = usePhotosStore();
  const [selected, setSelected] = useState<PhotoThumbnail | null>(null);

  useEffect(() => {
    initPhotosListeners();
  }, []);

  useEffect(() => {
    if (loaded && photos.length === 0 && !isLoadingMore) loadNextPage();
  }, [loaded, photos.length, isLoadingMore]);

  const months = useMemo(() => groupByMonth(photos), [photos]);

  return (
    <div className="flex h-full flex-col">
      <SectionHeader
        section={sectionMeta("photos")}
        subtitle={`${photos.length} photo${photos.length === 1 ? "" : "s"}${hasMore ? "+" : ""}`}
      />

      <div className="flex-1 overflow-y-auto">
        {loaded && photos.length === 0 && !isLoadingMore ? (
          <div className="flex h-full flex-col items-center justify-center gap-3 text-neutral-400 dark:text-neutral-500">
            <ImageIcon className="h-8 w-8" />
            <p className="text-sm">No photos loaded yet</p>
            <button
              onClick={() => loadNextPage()}
              className="rounded-lg bg-purple-500 px-3.5 py-1.5 text-[13px] font-medium text-white hover:bg-purple-600 transition-colors"
            >
              Load Photos
            </button>
          </div>
        ) : (
          <>
            {months.map(([month, monthPhotos]) => (
              <div key={month} className="pt-4">
                <h2 className="px-4 pb-2 text-[13px] font-semibold text-neutral-600 dark:text-neutral-300">
                  {month}
                </h2>
                <div
                  className="grid gap-1 px-4"
                  style={{ gridTemplateColumns: "repeat(auto-fill, minmax(110px, 160px))" }}
                >
                  {monthPhotos.map((photo) => (
                    <button
                      key={photo.id}
                      onClick={() => {
                        setSelected(photo);
                        requestPhotoFull(photo.id);
                      }}
                      className="aspect-square overflow-hidden rounded-[4px] bg-black/5 dark:bg-white/10"
                    >
                      <img
                        src={`data:image/jpeg;base64,${photo.thumbnailBase64}`}
                        alt=""
                        className="h-full w-full object-cover"
                      />
                    </button>
                  ))}
                </div>
              </div>
            ))}

            <div className="flex justify-center p-6">
              {isLoadingMore ? (
                <Loader2 className="h-5 w-5 animate-spin text-neutral-400" />
              ) : hasMore ? (
                <button
                  onClick={() => loadNextPage()}
                  className="rounded-lg border border-black/10 dark:border-white/15 px-4 py-1.5 text-[13px] font-medium text-neutral-600 dark:text-neutral-300 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
                >
                  Load More
                </button>
              ) : null}
            </div>
          </>
        )}
      </div>

      <AnimatePresence>
        {selected && <PhotoLightbox photo={selected} onClose={() => setSelected(null)} />}
      </AnimatePresence>
    </div>
  );
}

function PhotoLightbox({ photo, onClose }: { photo: PhotoThumbnail; onClose: () => void }) {
  const fullImage = usePhotosStore((s) => s.fullImages[photo.id]);

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-8"
    >
      <button
        onClick={onClose}
        className="absolute right-6 top-6 rounded-full bg-white/10 p-2 text-white hover:bg-white/20 transition-colors"
      >
        <X className="h-5 w-5" />
      </button>
      {fullImage ? (
        <motion.img
          initial={{ scale: 0.96, opacity: 0 }}
          animate={{ scale: 1, opacity: 1 }}
          onClick={(e) => e.stopPropagation()}
          src={`data:${fullImage.mimeType};base64,${fullImage.dataBase64}`}
          alt=""
          className="max-h-full max-w-full rounded-lg object-contain"
        />
      ) : (
        <div
          onClick={(e) => e.stopPropagation()}
          className="flex h-[300px] w-[400px] flex-col items-center justify-center gap-3 rounded-lg bg-white/5 text-white/70"
        >
          <Loader2 className="h-6 w-6 animate-spin" />
          <p className="text-sm">Loading full-resolution photo…</p>
        </div>
      )}
    </motion.div>
  );
}
