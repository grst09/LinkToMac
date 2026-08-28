import { useCallback, useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
  Check,
  CheckSquare,
  ChevronLeft,
  ChevronRight,
  Image as ImageIcon,
  Loader2,
  RefreshCw,
  Square,
  Trash2,
  X,
} from "lucide-react";
import { SectionHeader } from "./SectionHeader";
import { ConfirmDialog } from "./ContactDetail";
import { sectionMeta } from "../theme/sections";
import {
  deletePhotos,
  initPhotosListeners,
  loadNextPage,
  refreshPhotos,
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
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [isSelecting, setIsSelecting] = useState(false);
  const [checkedIds, setCheckedIds] = useState<Set<string>>(new Set());
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  useEffect(() => {
    initPhotosListeners();
  }, []);

  useEffect(() => {
    if (loaded && photos.length === 0 && !isLoadingMore) loadNextPage();
  }, [loaded, photos.length, isLoadingMore]);

  // A deleted/no-longer-loaded photo (e.g. the phone's own reset-and-re-page cycle ran while the
  // lightbox was open) shouldn't leave it pointing at a stale id.
  useEffect(() => {
    if (selectedId && !photos.some((p) => p.id === selectedId)) setSelectedId(null);
  }, [photos, selectedId]);

  const months = useMemo(() => groupByMonth(photos), [photos]);
  const allChecked = photos.length > 0 && photos.every((p) => checkedIds.has(p.id));

  const selectedIndex = selectedId ? photos.findIndex((p) => p.id === selectedId) : -1;
  const selected = selectedIndex >= 0 ? photos[selectedIndex] : null;

  const goTo = useCallback(
    (delta: number) => {
      setSelectedId((current) => {
        const idx = current ? photos.findIndex((p) => p.id === current) : -1;
        const next = photos[idx + delta];
        if (!next) return current;
        requestPhotoFull(next.id);
        return next.id;
      });
    },
    [photos],
  );

  function toggleChecked(id: string) {
    setCheckedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function openPhoto(photo: PhotoThumbnail) {
    setSelectedId(photo.id);
    requestPhotoFull(photo.id);
  }

  return (
    <div className="flex h-full flex-col">
      <SectionHeader
        section={sectionMeta("photos")}
        subtitle={
          isSelecting
            ? `${checkedIds.size} selected`
            : `${photos.length} photo${photos.length === 1 ? "" : "s"}${hasMore ? "+" : ""}`
        }
        trailing={
          <div className="flex items-center gap-1.5">
            <IconButton icon={RefreshCw} title="Resync" onClick={() => refreshPhotos()} />
            {!isSelecting ? (
              photos.length > 0 && <IconButton icon={CheckSquare} title="Select" onClick={() => setIsSelecting(true)} />
            ) : (
              <>
                {checkedIds.size > 0 && (
                  <button
                    onClick={() => setConfirmingDelete(true)}
                    className="flex items-center gap-1 rounded-md border border-red-500/30 px-2 py-1 text-xs font-medium text-red-600 dark:text-red-400 hover:bg-red-500/10 transition-colors"
                  >
                    <Trash2 className="h-3 w-3" />
                    Delete ({checkedIds.size})
                  </button>
                )}
                <IconButton
                  icon={allChecked ? Square : CheckSquare}
                  title={allChecked ? "Deselect All" : "Select All"}
                  onClick={() => setCheckedIds(allChecked ? new Set() : new Set(photos.map((p) => p.id)))}
                />
                <button
                  onClick={() => {
                    setIsSelecting(false);
                    setCheckedIds(new Set());
                  }}
                  className="rounded-md px-2 py-1 text-xs font-medium text-neutral-500 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
                >
                  Done
                </button>
              </>
            )}
          </div>
        }
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
                  {monthPhotos.map((photo) => {
                    const checked = checkedIds.has(photo.id);
                    return (
                      <motion.button
                        key={photo.id}
                        whileHover={{ scale: 1.03 }}
                        whileTap={{ scale: 0.98 }}
                        transition={{ type: "spring", stiffness: 400, damping: 25 }}
                        onClick={() => (isSelecting ? toggleChecked(photo.id) : openPhoto(photo))}
                        className="relative aspect-square overflow-hidden rounded-lg bg-black/5 dark:bg-white/10 shadow-soft"
                      >
                        <img
                          src={`data:image/jpeg;base64,${photo.thumbnailBase64}`}
                          alt=""
                          className={`h-full w-full object-cover transition-opacity ${
                            isSelecting && !checked ? "opacity-70" : ""
                          }`}
                        />
                        {isSelecting && (
                          <div
                            className={`absolute right-1.5 top-1.5 flex h-5 w-5 items-center justify-center rounded-full border transition-colors ${
                              checked
                                ? "border-blue-500 bg-blue-500 text-white"
                                : "border-white/70 bg-black/30 text-transparent"
                            }`}
                          >
                            <Check className="h-3 w-3" />
                          </div>
                        )}
                      </motion.button>
                    );
                  })}
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
        {selected && (
          <PhotoLightbox
            photo={selected}
            hasPrev={selectedIndex > 0}
            hasNext={selectedIndex < photos.length - 1}
            onPrev={() => goTo(-1)}
            onNext={() => goTo(1)}
            onClose={() => setSelectedId(null)}
          />
        )}
      </AnimatePresence>

      {confirmingDelete && (
        <ConfirmDialog
          title={`Delete ${checkedIds.size} Photo${checkedIds.size === 1 ? "" : "s"}?`}
          message="Your phone will ask you to confirm — this can't be undone once you do."
          onCancel={() => setConfirmingDelete(false)}
          onConfirm={async () => {
            await deletePhotos(Array.from(checkedIds));
            setCheckedIds(new Set());
            setIsSelecting(false);
            setConfirmingDelete(false);
          }}
        />
      )}
    </div>
  );
}

function IconButton({
  icon: Icon,
  title,
  onClick,
}: {
  icon: typeof CheckSquare;
  title: string;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      className="rounded-md p-1.5 text-neutral-500 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
    >
      <Icon className="h-3.5 w-3.5" />
    </button>
  );
}

function PhotoLightbox({
  photo,
  hasPrev,
  hasNext,
  onPrev,
  onNext,
  onClose,
}: {
  photo: PhotoThumbnail;
  hasPrev: boolean;
  hasNext: boolean;
  onPrev: () => void;
  onNext: () => void;
  onClose: () => void;
}) {
  const fullImage = usePhotosStore((s) => s.fullImages[photo.id]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
      else if (e.key === "ArrowLeft") onPrev();
      else if (e.key === "ArrowRight") onNext();
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose, onPrev, onNext]);

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

      {hasPrev && (
        <button
          onClick={(e) => {
            e.stopPropagation();
            onPrev();
          }}
          title="Previous photo (←)"
          className="absolute left-6 top-1/2 -translate-y-1/2 rounded-full bg-white/10 p-2 text-white hover:bg-white/20 transition-colors"
        >
          <ChevronLeft className="h-6 w-6" />
        </button>
      )}
      {hasNext && (
        <button
          onClick={(e) => {
            e.stopPropagation();
            onNext();
          }}
          title="Next photo (→)"
          className="absolute right-6 top-1/2 -translate-y-1/2 rounded-full bg-white/10 p-2 text-white hover:bg-white/20 transition-colors"
        >
          <ChevronRight className="h-6 w-6" />
        </button>
      )}

      {fullImage ? (
        <motion.img
          key={photo.id}
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
