import { useEffect, useMemo, useRef, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import {
  ChevronRight,
  ChevronUp,
  ChevronDown,
  FolderPlus,
  LayoutGrid,
  List,
  Upload,
  File as FileIcon,
  Loader2,
  CheckCircle2,
  AlertTriangle,
  Folder,
  MoreHorizontal,
  Files,
  PanelRight,
} from "lucide-react";
import { SectionHeader } from "./SectionHeader";
import { SegmentedControl } from "./SegmentedControl";
import { ConfirmDialog } from "./ContactDetail";
import { sectionMeta } from "../theme/sections";
import { folderIconFor } from "../theme/folderIcons";
import { fileTypeInfo, isPreviewable, isPreviewableVideo, isPreviewableAudio } from "../theme/fileTypeIcons";
import { formatBytes, fileToBase64 } from "../utils/formatBytes";
import { formatFullDate } from "../utils/relativeTime";
import { useConnectionStore } from "../store/connection";
import {
  useFilesStore,
  initFilesListeners,
  listFiles,
  downloadFile,
  uploadFile,
  createFolder,
  renameFile,
  deleteFile,
  cutToClipboard,
  copyToClipboard,
  pasteClipboard,
  setViewMode,
  joinPath,
  parentPath,
  previewFile,
  clearPreview,
  type FileEntry,
} from "../store/files";

interface MenuState {
  x: number;
  y: number;
  entry: FileEntry | null; // null = background menu
}

type SortKey = "name" | "kind" | "modified" | "size";
interface SortState {
  key: SortKey;
  direction: "asc" | "desc";
}

const PREVIEW_WIDTH_STORAGE_KEY = "linktomac-files-preview-width";
const PREVIEW_WIDTH_DEFAULT = 440;
const PREVIEW_WIDTH_MIN = 280;
// A wider panel isn't a layout bug — the math checks out — but with nothing selected it's just
// a small centered icon adrift in a lot of empty space, which reads as broken even though it
// isn't. Capping it well short of "eats half the window" keeps that empty state from being the
// dominant thing on screen.
const PREVIEW_WIDTH_MAX = 560;
// Widest the sidebar ever is (it shrinks when collapsed, never grows past this), plus roughly
// what the table needs at its own column minimums, so a panel width that made sense in a wide
// window can't eat nearly the whole left side after the window is later made much narrower —
// that's what "a stale 720px-wide panel leaves ~140px for the whole file list" looks like.
const SIDEBAR_WIDTH_ESTIMATE = 224;
const MIN_LEFT_AREA = 440;

function clampPreviewWidthToWindow(width: number, windowWidth: number): number {
  const dynamicMax = Math.max(PREVIEW_WIDTH_MIN, windowWidth - SIDEBAR_WIDTH_ESTIMATE - MIN_LEFT_AREA);
  return Math.min(width, PREVIEW_WIDTH_MAX, dynamicMax);
}

function loadPreviewWidth(): number {
  try {
    const stored = Number(localStorage.getItem(PREVIEW_WIDTH_STORAGE_KEY));
    if (stored >= PREVIEW_WIDTH_MIN && stored <= PREVIEW_WIDTH_MAX) return stored;
  } catch {
    // localStorage can throw in restricted contexts — the default width is a fine fallback.
  }
  return PREVIEW_WIDTH_DEFAULT;
}

// Kind/Modified/Size are fixed, user-controlled widths, same as Finder's list view. Name is
// the same fixed-px column when the user has dragged it (so the drag tracks the cursor exactly —
// a CSS `1fr` track always wins over a lower floor when there's slack, which made dragging while
// there was leftover space visually do nothing), but *auto-fills* remaining window width whenever
// the user hasn't touched its handle yet, so a wide window doesn't show dead space after the
// table by default. `nameAutoFill` tracks which mode Name is in; dragging its handle switches it
// to manual (the exact-drag-tracking mode) and persists, same as the width itself. Size isn't
// resizable — it's the last content column, with nothing to its right to negotiate space with —
// so it's just a constant, not part of this state.
type ColumnKey = "name" | "kind" | "modified";
const SIZE_COLUMN_WIDTH = 90;
const COLUMN_WIDTH_STORAGE_KEY = "linktomac-files-column-widths";
const NAME_AUTOFILL_STORAGE_KEY = "linktomac-files-name-autofill";
const COLUMN_DEFAULTS: Record<ColumnKey, number> = { name: 280, kind: 150, modified: 150 };
// Name has no upper bound: auto-fill mode already grows it unbounded to fill leftover window
// space, so a manual max would make grabbing its handle from a wide auto-filled state snap it
// back down the instant the drag starts, instead of continuing smoothly from wherever it was.
const COLUMN_LIMITS: Record<ColumnKey, { min: number; max: number }> = {
  name: { min: 120, max: Infinity },
  kind: { min: 80, max: 280 },
  modified: { min: 90, max: 240 },
};

function clampColumnWidth(column: ColumnKey, width: number): number {
  const { min, max } = COLUMN_LIMITS[column];
  return Number.isFinite(width) ? Math.min(max, Math.max(min, width)) : COLUMN_DEFAULTS[column];
}

function loadColumnWidths(): Record<ColumnKey, number> {
  try {
    const stored = JSON.parse(localStorage.getItem(COLUMN_WIDTH_STORAGE_KEY) ?? "null");
    if (stored && typeof stored === "object") {
      return {
        name: clampColumnWidth("name", Number(stored.name)),
        kind: clampColumnWidth("kind", Number(stored.kind)),
        modified: clampColumnWidth("modified", Number(stored.modified)),
      };
    }
  } catch {
    // localStorage/JSON can throw in restricted contexts — defaults are a fine fallback.
  }
  return { ...COLUMN_DEFAULTS };
}

function loadNameAutoFill(): boolean {
  try {
    const stored = localStorage.getItem(NAME_AUTOFILL_STORAGE_KEY);
    return stored === null ? true : stored === "true";
  } catch {
    return true;
  }
}

// Fixed chrome around the table that isn't a content column, subtracted from the container's
// measured width to get how much room Name can claim in auto-fill mode. Both the header and row
// grids share these exact classes (gap-2.5 px-3 on 6 tracks), so this has to mirror them or the
// computed width silently overflows the container by the missing amount:
//   - checkbox (28px) + Size (SIZE_COLUMN_WIDTH) + menu-button (32px) tracks
//   - 5 gaps between the 6 grid tracks, gap-2.5 = 10px each
//   - the grid's own px-3 padding, 12px on both sides
//   - the outer card's px-4 padding (16px both sides) and its 1px border on both sides
const TABLE_FIXED_COLUMNS_WIDTH = 28 + SIZE_COLUMN_WIDTH + 32;
const TABLE_CARD_CHROME_WIDTH = 5 * 10 + 12 * 2 + 16 * 2 + 2;

export function FilesView() {
  const {
    currentPath,
    entries,
    clipboard,
    viewMode,
    uploadingFileName,
    successMessage,
    errorMessage,
    loaded,
    previewData,
    previewLoading,
  } = useFilesStore();
  const deviceName = useConnectionStore((s) => s.deviceName);

  const [selectedNames, setSelectedNames] = useState<Set<string>>(new Set());
  const [anchorName, setAnchorName] = useState<string | null>(null);
  const [sort, setSort] = useState<SortState>({ key: "name", direction: "asc" });
  const [menu, setMenu] = useState<MenuState | null>(null);
  const [renaming, setRenaming] = useState<FileEntry | null>(null);
  const [creatingFolder, setCreatingFolder] = useState(false);
  const [deleting, setDeleting] = useState<FileEntry[] | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [marqueeRect, setMarqueeRect] = useState<{ left: number; top: number; width: number; height: number } | null>(null);
  const [previewWidth, setPreviewWidthState] = useState<number>(loadPreviewWidth);
  const [columnWidths, setColumnWidthsState] = useState<Record<ColumnKey, number>>(loadColumnWidths);
  const [nameAutoFill, setNameAutoFillState] = useState<boolean>(loadNameAutoFill);
  const [windowWidth, setWindowWidth] = useState<number>(() => window.innerWidth);
  const [containerWidth, setContainerWidth] = useState(0);

  const containerRef = useRef<HTMLDivElement>(null);
  const rowRefs = useRef<Map<string, HTMLElement>>(new Map());
  const previewWidthRef = useRef(previewWidth);
  const previewResizeRef = useRef<{ startX: number; startWidth: number } | null>(null);
  const columnWidthsRef = useRef(columnWidths);
  const nameAutoFillRef = useRef(nameAutoFill);
  const columnResizeRef = useRef<{ column: ColumnKey; startX: number; startWidth: number } | null>(null);
  const dragRef = useRef<{
    active: boolean;
    startClientX: number;
    startClientY: number;
    additive: boolean;
    baseSelection: Set<string>;
  } | null>(null);

  useEffect(() => {
    initFilesListeners();
  }, []);

  // The table can be wider than the available space (Name has a minimum, Kind/Modified/Size are
  // fixed), so the container scrolls horizontally rather than clipping columns. Browsers try to
  // preserve scroll position across a resize, which for a horizontal scroller means the visible
  // window can land somewhere confusing — e.g. showing Kind/Modified/Size but not Name, or vice
  // versa — depending on the exact resize path taken. Pinning scrollLeft back to 0 on every
  // resize keeps Name (the column that actually matters) always the anchor. Also tracks the
  // container's own measured width, which effectiveNameWidth below uses to auto-fill Name.
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const observer = new ResizeObserver(() => {
      el.scrollLeft = 0;
      setContainerWidth(el.clientWidth);
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // Tracks the OS window's own width so the preview panel's *rendered* width can be clamped
  // live against it — see effectivePreviewWidth below. This deliberately doesn't touch
  // `previewWidth` itself (the user's remembered/dragged preference): shrinking the window
  // should shrink the panel on screen, but widening it back should let the panel grow back to
  // what the user actually chose, not get stuck at whatever it was squeezed down to.
  useEffect(() => {
    function handleWindowResize() {
      setWindowWidth(window.innerWidth);
    }
    window.addEventListener("resize", handleWindowResize);
    return () => window.removeEventListener("resize", handleWindowResize);
  }, []);

  const effectivePreviewWidth = useMemo(
    () => clampPreviewWidthToWindow(previewWidth, windowWidth),
    [previewWidth, windowWidth],
  );

  useEffect(() => {
    if (loaded && entries.length === 0 && currentPath === "") listFiles("");
  }, [loaded]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    clearSelection();
  }, [currentPath]); // eslint-disable-line react-hooks/exhaustive-deps

  // Folders always lead (Finder-style), each group sorted independently by the active column.
  const sortedEntries = useMemo(() => {
    const dirs = entries.filter((e) => e.isDirectory);
    const files = entries.filter((e) => !e.isDirectory);
    const compare = (a: FileEntry, b: FileEntry) => {
      let result: number;
      switch (sort.key) {
        case "modified":
          result = a.modifiedAt - b.modifiedAt;
          break;
        case "size":
          result = a.sizeBytes - b.sizeBytes;
          break;
        case "kind":
          result = fileTypeInfo(a.name, a.isDirectory).kind.localeCompare(fileTypeInfo(b.name, b.isDirectory).kind);
          break;
        default:
          result = a.name.localeCompare(b.name);
      }
      return sort.direction === "asc" ? result : -result;
    };
    return [...dirs.sort(compare), ...files.sort(compare)];
  }, [entries, sort]);

  // The order selection/keyboard-nav should follow — sorted for the table and preview list,
  // natural for the grid.
  const displayedEntries = viewMode === "grid" ? entries : sortedEntries;
  // Name's *rendered* width: while nameAutoFill is on (the default, and after double-clicking
  // its handle to reset), it fills whatever room is left in the container so a wide window
  // doesn't show dead space after the table. The instant the user drags its handle, nameAutoFill
  // flips off and this becomes the plain stored width — a real pixel value, not a CSS `1fr`
  // track, so the drag tracks the cursor 1:1 instead of being overridden by the flexible track
  // (that mismatch, confirmed by direct measurement, was the bug in the previous version of
  // this). Computed in JS rather than CSS grid because the drag handler below needs this exact
  // same number as its start-of-drag reference, whichever mode produced it.
  const effectiveNameWidth = useMemo(() => {
    if (!nameAutoFill) return columnWidths.name;
    const available =
      containerWidth - TABLE_CARD_CHROME_WIDTH - TABLE_FIXED_COLUMNS_WIDTH - columnWidths.kind - columnWidths.modified;
    return Math.max(columnWidths.name, available);
  }, [nameAutoFill, columnWidths.name, columnWidths.kind, columnWidths.modified, containerWidth]);

  // Checkbox, then every content column at its own pixel width, then the menu button.
  const tableGridTemplate = `28px ${effectiveNameWidth}px ${columnWidths.kind}px ${columnWidths.modified}px ${SIZE_COLUMN_WIDTH}px 32px`;
  const selectedEntries = useMemo(
    () => displayedEntries.filter((e) => selectedNames.has(e.name)),
    [displayedEntries, selectedNames],
  );
  const selectedEntry = selectedEntries.length === 1 ? selectedEntries[0] : null;

  // Fetches preview bytes only in Preview view, and only for the single-selected-image case —
  // every other state (grid/list view, no selection, multiple, a folder, a non-image file) just
  // renders synchronously from what's already in `entries`, no need to hit the wire.
  useEffect(() => {
    if (viewMode !== "preview" || !selectedEntry || selectedEntry.isDirectory || !isPreviewable(selectedEntry.name)) {
      clearPreview();
      return;
    }
    previewFile(joinPath(currentPath, selectedEntry.name));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewMode, selectedEntry?.name, selectedEntry?.isDirectory, currentPath]);

  const previewMediaSrc = useMemo(() => {
    if (!selectedEntry || selectedEntry.isDirectory || !isPreviewable(selectedEntry.name)) return null;
    const targetPath = joinPath(currentPath, selectedEntry.name);
    if (!previewData || previewData.path !== targetPath) return null;
    return `data:${previewData.mimeType ?? "application/octet-stream"};base64,${previewData.dataBase64}`;
  }, [selectedEntry, currentPath, previewData]);
  const previewIsVideo = !!selectedEntry && isPreviewableVideo(selectedEntry.name);
  const previewIsAudio = !!selectedEntry && isPreviewableAudio(selectedEntry.name);

  function toggleSort(key: SortKey) {
    setSort((prev) => (prev.key === key ? { key, direction: prev.direction === "asc" ? "desc" : "asc" } : { key, direction: "asc" }));
  }

  function selectSingle(name: string) {
    setSelectedNames(new Set([name]));
    setAnchorName(name);
  }

  function toggleSelection(name: string) {
    setSelectedNames((prev) => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
    setAnchorName(name);
  }

  function selectRange(name: string) {
    const anchor = anchorName ?? name;
    const anchorIdx = displayedEntries.findIndex((e) => e.name === anchor);
    const targetIdx = displayedEntries.findIndex((e) => e.name === name);
    if (anchorIdx === -1 || targetIdx === -1) {
      selectSingle(name);
      return;
    }
    const [from, to] = anchorIdx < targetIdx ? [anchorIdx, targetIdx] : [targetIdx, anchorIdx];
    setSelectedNames(new Set(displayedEntries.slice(from, to + 1).map((e) => e.name)));
  }

  function clearSelection() {
    setSelectedNames(new Set());
    setAnchorName(null);
  }

  function handleEntryClick(e: React.MouseEvent, entry: FileEntry) {
    if (e.shiftKey) selectRange(entry.name);
    else if (e.metaKey || e.ctrlKey) toggleSelection(entry.name);
    else selectSingle(entry.name);
  }

  // Click-and-drag rubber-band selection, Finder-style: mousedown outside any interactive
  // control arms it, movement past a small threshold turns it into a live selection rectangle
  // (shift/cmd held at mousedown unions with whatever was already selected), and mouseup on
  // empty space with no drag just clears the selection.
  function handleContainerMouseDown(e: React.MouseEvent) {
    const target = e.target as HTMLElement;
    if (target.closest("input,button,a")) return;
    if (e.button !== 0) return;
    e.preventDefault();

    const additive = e.shiftKey || e.metaKey || e.ctrlKey;
    dragRef.current = {
      active: false,
      startClientX: e.clientX,
      startClientY: e.clientY,
      additive,
      baseSelection: additive ? new Set(selectedNames) : new Set(),
    };
    window.addEventListener("mousemove", handleWindowMouseMove);
    window.addEventListener("mouseup", handleWindowMouseUp);
  }

  function handleWindowMouseMove(e: MouseEvent) {
    const drag = dragRef.current;
    const container = containerRef.current;
    if (!drag || !container) return;

    const dx = e.clientX - drag.startClientX;
    const dy = e.clientY - drag.startClientY;
    if (!drag.active && Math.hypot(dx, dy) < 4) return;
    drag.active = true;

    const containerRect = container.getBoundingClientRect();
    const left = Math.min(drag.startClientX, e.clientX);
    const right = Math.max(drag.startClientX, e.clientX);
    const top = Math.min(drag.startClientY, e.clientY);
    const bottom = Math.max(drag.startClientY, e.clientY);

    setMarqueeRect({
      left: left - containerRect.left + container.scrollLeft,
      top: top - containerRect.top + container.scrollTop,
      width: right - left,
      height: bottom - top,
    });

    const hits = new Set<string>(drag.additive ? drag.baseSelection : []);
    rowRefs.current.forEach((el, name) => {
      const r = el.getBoundingClientRect();
      if (r.left < right && r.right > left && r.top < bottom && r.bottom > top) hits.add(name);
    });
    setSelectedNames(hits);
  }

  function handleWindowMouseUp(e: MouseEvent) {
    window.removeEventListener("mousemove", handleWindowMouseMove);
    window.removeEventListener("mouseup", handleWindowMouseUp);
    const drag = dragRef.current;
    dragRef.current = null;
    if (!drag) return;

    if (!drag.active) {
      const target = e.target as HTMLElement;
      if (!target.closest("[data-file-name]")) clearSelection();
      return;
    }
    setMarqueeRect(null);
  }

  function setPreviewWidth(width: number) {
    const clamped = clampPreviewWidthToWindow(Math.max(PREVIEW_WIDTH_MIN, width), window.innerWidth);
    previewWidthRef.current = clamped;
    setPreviewWidthState(clamped);
  }

  // Drag handle on the preview panel's left edge — dragging left widens it (the panel's own
  // edge moves left), dragging right narrows it. Starts from the panel's actual on-screen width
  // (which may currently be window-clamped smaller than the user's remembered preference), not
  // the raw stored preference, so the drag never jumps the panel to a different size the instant
  // it starts.
  function handlePreviewResizeMouseDown(e: React.MouseEvent) {
    e.preventDefault();
    previewResizeRef.current = { startX: e.clientX, startWidth: effectivePreviewWidth };
    window.addEventListener("mousemove", handlePreviewResizeMouseMove);
    window.addEventListener("mouseup", handlePreviewResizeMouseUp);
  }

  function handlePreviewResizeMouseMove(e: MouseEvent) {
    const resize = previewResizeRef.current;
    if (!resize) return;
    setPreviewWidth(resize.startWidth + (resize.startX - e.clientX));
  }

  function handlePreviewResizeMouseUp() {
    window.removeEventListener("mousemove", handlePreviewResizeMouseMove);
    window.removeEventListener("mouseup", handlePreviewResizeMouseUp);
    previewResizeRef.current = null;
    try {
      localStorage.setItem(PREVIEW_WIDTH_STORAGE_KEY, String(previewWidthRef.current));
    } catch {
      // Best-effort persistence — losing the remembered width isn't worth surfacing an error.
    }
  }

  function setColumnWidth(column: ColumnKey, width: number) {
    const clamped = clampColumnWidth(column, width);
    const next = { ...columnWidthsRef.current, [column]: clamped };
    columnWidthsRef.current = next;
    setColumnWidthsState(next);
  }

  function setNameAutoFill(value: boolean) {
    nameAutoFillRef.current = value;
    setNameAutoFillState(value);
  }

  // Drag handle at a column's right edge, same table for List and Preview view. Stops the event
  // from bubbling to the container's own mousedown (which would otherwise also arm a marquee
  // selection drag at the same time). Name's startWidth is its current *rendered* width
  // (effectiveNameWidth) rather than the raw stored one, so the drag never jumps the column to a
  // different size the instant it starts — matters when Name is currently auto-filled wider than
  // its stored preference. Grabbing Name's handle also switches it out of auto-fill mode, since
  // from here on the user is expressing an exact width they want, not "fill what's left".
  function handleColumnResizeMouseDown(column: ColumnKey, e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    const startWidth = column === "name" ? effectiveNameWidth : columnWidths[column];
    if (column === "name" && nameAutoFillRef.current) setNameAutoFill(false);
    columnResizeRef.current = { column, startX: e.clientX, startWidth };
    window.addEventListener("mousemove", handleColumnResizeMouseMove);
    window.addEventListener("mouseup", handleColumnResizeMouseUp);
  }

  function handleColumnResizeMouseMove(e: MouseEvent) {
    const resize = columnResizeRef.current;
    if (!resize) return;
    setColumnWidth(resize.column, resize.startWidth + (e.clientX - resize.startX));
  }

  function handleColumnResizeMouseUp() {
    window.removeEventListener("mousemove", handleColumnResizeMouseMove);
    window.removeEventListener("mouseup", handleColumnResizeMouseUp);
    columnResizeRef.current = null;
    try {
      localStorage.setItem(COLUMN_WIDTH_STORAGE_KEY, JSON.stringify(columnWidthsRef.current));
      localStorage.setItem(NAME_AUTOFILL_STORAGE_KEY, String(nameAutoFillRef.current));
    } catch {
      // Best-effort persistence — losing the remembered widths isn't worth surfacing an error.
    }
  }

  // Double-clicking Name's handle resets it to auto-fill mode (Excel/Finder-style "autofit"),
  // in case the user wants the fill-the-window behavior back after having dragged it manually.
  // Also resets the stored width back to its default — otherwise a previous manual drag would
  // stick around as auto-fill's floor (Math.max(columnWidths.name, available)), so a column
  // dragged very wide once could never shrink below that again even in "auto" mode, which isn't
  // what a reset should do.
  function handleNameResizeDoubleClick(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    setNameAutoFill(true);
    setColumnWidth("name", COLUMN_DEFAULTS.name);
    try {
      localStorage.setItem(NAME_AUTOFILL_STORAGE_KEY, "true");
      localStorage.setItem(COLUMN_WIDTH_STORAGE_KEY, JSON.stringify(columnWidthsRef.current));
    } catch {
      // Best-effort persistence — losing the remembered mode isn't worth surfacing an error.
    }
  }

  // Finder-style shortcuts for the currently-selected item: Enter opens it, Delete/Backspace
  // removes it (via the same confirm dialog as the context menu), Cmd+C/X/V copy/cut/paste,
  // Cmd+Shift+N makes a new folder, Cmd+Up goes to the parent folder, arrow keys move the
  // selection, and Escape backs out of whatever's open (menu, then selection).
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      // Rename/new-folder/delete dialogs handle their own keys (Enter to confirm, Escape to
      // cancel) — don't let those keystrokes also trigger a file-list shortcut underneath.
      if (renaming || creatingFolder || deleting) return;

      const target = e.target as HTMLElement | null;
      const isTyping =
        !!target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable);
      if (isTyping) return;

      const meta = e.metaKey || e.ctrlKey;

      if (menu) {
        if (e.key === "Escape") setMenu(null);
        return;
      }

      if (meta && e.key.toLowerCase() === "c") {
        if (selectedEntry) {
          e.preventDefault();
          copyToClipboard(joinPath(currentPath, selectedEntry.name), selectedEntry.name);
        }
        return;
      }
      if (meta && e.key.toLowerCase() === "x") {
        if (selectedEntry) {
          e.preventDefault();
          cutToClipboard(joinPath(currentPath, selectedEntry.name), selectedEntry.name);
        }
        return;
      }
      if (meta && e.key.toLowerCase() === "v") {
        if (clipboard) {
          e.preventDefault();
          pasteClipboard();
        }
        return;
      }
      if (meta && e.shiftKey && e.key.toLowerCase() === "n") {
        e.preventDefault();
        setCreatingFolder(true);
        return;
      }
      if (meta && e.key === "ArrowUp") {
        if (currentPath) {
          e.preventDefault();
          listFiles(parentPath(currentPath));
        }
        return;
      }
      if (e.key === "Enter") {
        if (selectedEntry) {
          e.preventDefault();
          openEntry(selectedEntry);
        }
        return;
      }
      if (e.key === "Backspace" || e.key === "Delete") {
        if (selectedEntries.length > 0) {
          e.preventDefault();
          setDeleting(selectedEntries);
        }
        return;
      }
      if (e.key === "ArrowDown" || e.key === "ArrowRight" || e.key === "ArrowUp" || e.key === "ArrowLeft") {
        if (displayedEntries.length === 0) return;
        e.preventDefault();
        const forward = e.key === "ArrowDown" || e.key === "ArrowRight";
        const currentIndex = anchorName ? displayedEntries.findIndex((entry) => entry.name === anchorName) : -1;
        const nextIndex = currentIndex === -1
          ? (forward ? 0 : displayedEntries.length - 1)
          : Math.min(displayedEntries.length - 1, Math.max(0, currentIndex + (forward ? 1 : -1)));
        selectSingle(displayedEntries[nextIndex].name);
        return;
      }
      if (e.key === "Escape") {
        if (selectedNames.size > 0) clearSelection();
        return;
      }
    }

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [anchorName, selectedNames, selectedEntry, selectedEntries, displayedEntries, currentPath, clipboard, menu, renaming, creatingFolder, deleting]);

  const breadcrumbs = useMemo(() => {
    const segments: { name: string; path: string }[] = [{ name: "Device", path: "" }];
    if (currentPath) {
      let acc = "";
      for (const part of currentPath.split("/")) {
        acc = acc ? `${acc}/${part}` : part;
        segments.push({ name: part, path: acc });
      }
    }
    return segments;
  }, [currentPath]);

  function openEntry(entry: FileEntry) {
    const full = joinPath(currentPath, entry.name);
    if (entry.isDirectory) {
      listFiles(full);
    } else {
      downloadFile(full, true);
    }
  }

  async function handleDrop(e: React.DragEvent) {
    e.preventDefault();
    setDragOver(false);
    for (const file of Array.from(e.dataTransfer.files)) {
      const dataBase64 = await fileToBase64(file);
      uploadFile(currentPath, file.name, dataBase64, file.type || "application/octet-stream");
    }
  }

  return (
    <div className="flex h-full select-none" onClick={() => setMenu(null)}>
    <div className="flex h-full min-w-0 flex-1 flex-col">
      <SectionHeader section={sectionMeta("files")} subtitle={`Browsing ${deviceName ?? "device"}`} />

      <div className="px-4 pt-3">
        <div
          onDragOver={(e) => {
            e.preventDefault();
            setDragOver(true);
          }}
          onDragLeave={() => setDragOver(false)}
          onDrop={handleDrop}
          className={`flex h-[110px] flex-col items-center justify-center gap-1.5 rounded-2xl border-[1.5px] border-dashed transition-colors ${
            dragOver ? "border-blue-500 bg-blue-500/[0.08]" : "border-neutral-300/60 dark:border-neutral-600/60"
          }`}
        >
          <Upload className="h-5 w-5 text-neutral-400" />
          <p className="text-[13px] font-semibold text-neutral-600 dark:text-neutral-300">
            Drag files here to upload
          </p>
          <p className="text-xs text-neutral-400">Supports files and multiple items</p>
        </div>

        {uploadingFileName && (
          <Banner icon={Loader2} spin tint="blue" text={`Uploading ${uploadingFileName}…`} />
        )}
        {successMessage && <Banner icon={CheckCircle2} tint="emerald" text={successMessage} />}
        {errorMessage && <Banner icon={AlertTriangle} tint="orange" text={errorMessage} />}
      </div>

      <div className="flex items-center gap-2 px-4 py-2.5">
        <button
          onClick={() => listFiles(parentPath(currentPath))}
          disabled={!currentPath}
          className="rounded-md border border-black/10 dark:border-white/15 px-2.5 py-1 text-xs font-medium text-neutral-600 dark:text-neutral-300 disabled:opacity-40 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
        >
          Back
        </button>
        <button
          onClick={() => setCreatingFolder(true)}
          className="flex items-center gap-1 rounded-md border border-black/10 dark:border-white/15 px-2.5 py-1 text-xs font-medium text-neutral-600 dark:text-neutral-300 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
        >
          <FolderPlus className="h-3.5 w-3.5" />
          New Folder
        </button>
        <div className="flex-1" />
        <SegmentedControl
          layoutId="files-viewmode"
          iconOnly
          value={viewMode}
          onChange={setViewMode}
          options={[
            { value: "list", label: "List view", icon: List },
            { value: "grid", label: "Grid view", icon: LayoutGrid },
            { value: "preview", label: "Preview view", icon: PanelRight },
          ]}
        />
      </div>

      <div
        ref={containerRef}
        className="relative flex-1 overflow-y-auto"
        onMouseDown={handleContainerMouseDown}
        onContextMenu={(e) => {
          e.preventDefault();
          setMenu({ x: e.clientX, y: e.clientY, entry: null });
        }}
      >
        {entries.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-2 text-neutral-400 dark:text-neutral-500">
            <Folder className="h-8 w-8" />
            <p className="text-sm">This folder is empty</p>
          </div>
        ) : viewMode === "grid" ? (
          <div
            className="grid gap-4 p-4"
            style={{ gridTemplateColumns: "repeat(auto-fill, minmax(90px, 1fr))" }}
          >
            {displayedEntries.map((entry) => (
              <FileGridItem
                key={entry.name}
                entry={entry}
                selected={selectedNames.has(entry.name)}
                cutPending={clipboard?.operation === "cut" && clipboard.name === entry.name}
                registerRef={(el) => {
                  if (el) rowRefs.current.set(entry.name, el);
                  else rowRefs.current.delete(entry.name);
                }}
                onClick={(e) => handleEntryClick(e, entry)}
                onDoubleClick={() => openEntry(entry)}
                onContextMenu={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  if (!selectedNames.has(entry.name)) selectSingle(entry.name);
                  setMenu({ x: e.clientX, y: e.clientY, entry });
                }}
              />
            ))}
          </div>
        ) : (
          // "list" and "preview" share this exact table — Preview just adds the panel on the
          // right (below), it doesn't get its own row styling.
          <motion.div
            key={viewMode === "preview" ? "table-preview" : "table-list"}
            initial={{ opacity: 0, y: 4 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.18, ease: "easeOut" }}
            className="px-4 pb-4"
          >
            {/* w-fit — not a stretched block — so the card is exactly as wide as its columns add
                up to. That's still correct now that Name auto-fills leftover space: its
                *content* width already equals the available room (see effectiveNameWidth), so
                the card naturally spans the container in auto-fill mode and shrink-wraps down
                once the user drags Name to an explicit, possibly narrower, width. */}
            <div className="w-fit rounded-2xl border border-black/[0.06] dark:border-white/10 shadow-soft">
              <FileTableHeader
                sort={sort}
                onSort={toggleSort}
                allChecked={displayedEntries.length > 0 && selectedNames.size === displayedEntries.length}
                someChecked={selectedNames.size > 0 && selectedNames.size < displayedEntries.length}
                onToggleAll={() =>
                  setSelectedNames(
                    selectedNames.size === displayedEntries.length
                      ? new Set()
                      : new Set(displayedEntries.map((e) => e.name)),
                  )
                }
                gridTemplateColumns={tableGridTemplate}
                onColumnResizeStart={handleColumnResizeMouseDown}
                onNameResizeDoubleClick={handleNameResizeDoubleClick}
              />
              {/* No overflow-hidden here — Name's minmax floor means content can occasionally
                  need more width than is available (a very narrow window, or a wide Preview
                  panel), and clipping it silently (the previous bug) is worse than letting the
                  scrollable container above (which already scrolls horizontally, same ancestor
                  as the sticky header) handle it — the rounded bottom corners are a fair trade
                  for that. */}
              <div className="rounded-b-2xl divide-y divide-black/[0.05] dark:divide-white/[0.06]">
                {displayedEntries.map((entry) => (
                  <FileTableRow
                    key={entry.name}
                    entry={entry}
                    selected={selectedNames.has(entry.name)}
                    gridTemplateColumns={tableGridTemplate}
                    cutPending={clipboard?.operation === "cut" && clipboard.name === entry.name}
                    registerRef={(el) => {
                      if (el) rowRefs.current.set(entry.name, el);
                      else rowRefs.current.delete(entry.name);
                    }}
                    onClick={(e) => handleEntryClick(e, entry)}
                    onDoubleClick={() => openEntry(entry)}
                    onToggleChecked={() => toggleSelection(entry.name)}
                    onContextMenu={(e) => {
                      e.preventDefault();
                      e.stopPropagation();
                      if (!selectedNames.has(entry.name)) selectSingle(entry.name);
                      setMenu({ x: e.clientX, y: e.clientY, entry });
                    }}
                    onMenuButton={(e) => {
                      e.stopPropagation();
                      const rect = e.currentTarget.getBoundingClientRect();
                      if (!selectedNames.has(entry.name)) selectSingle(entry.name);
                      setMenu({ x: rect.right - 160, y: rect.bottom + 4, entry });
                    }}
                  />
                ))}
              </div>
            </div>
          </motion.div>
        )}

        {marqueeRect && (
          <div
            className="pointer-events-none absolute z-10 rounded-sm border border-blue-500/60 bg-blue-500/[0.12]"
            style={{
              left: marqueeRect.left,
              top: marqueeRect.top,
              width: marqueeRect.width,
              height: marqueeRect.height,
            }}
          />
        )}
      </div>

      <div className="border-t border-black/5 dark:border-white/10" />
      <div className="flex items-center justify-between px-4 py-2 text-xs text-neutral-500 dark:text-neutral-400">
        <div className="flex items-center gap-1">
          {breadcrumbs.map((seg, i) => (
            <span key={seg.path} className="flex items-center gap-1">
              {i > 0 && <ChevronRight className="h-3 w-3" />}
              <button onClick={() => listFiles(seg.path)} className="hover:text-neutral-800 dark:hover:text-neutral-200">
                {seg.name}
              </button>
            </span>
          ))}
        </div>
        <span>
          {entries.length} item{entries.length === 1 ? "" : "s"}
        </span>
      </div>
    </div>

      {viewMode === "preview" && (
        <FilePreviewPanel
          selectedEntries={selectedEntries}
          mediaSrc={previewMediaSrc}
          isVideo={previewIsVideo}
          isAudio={previewIsAudio}
          loading={previewLoading && !previewMediaSrc}
          width={effectivePreviewWidth}
          onResizeStart={handlePreviewResizeMouseDown}
        />
      )}

      {menu && (
        <ContextMenu
          menu={menu}
          hasClipboard={!!clipboard}
          onClose={() => setMenu(null)}
          onRename={() => menu.entry && setRenaming(menu.entry)}
          onDownload={() => menu.entry && downloadFile(joinPath(currentPath, menu.entry.name), false)}
          onCut={() => menu.entry && cutToClipboard(joinPath(currentPath, menu.entry.name), menu.entry.name)}
          onCopy={() => menu.entry && copyToClipboard(joinPath(currentPath, menu.entry.name), menu.entry.name)}
          onPaste={() => pasteClipboard()}
          onDelete={() => {
            if (!menu.entry) return;
            const bulk = selectedNames.has(menu.entry.name) && selectedEntries.length > 1;
            setDeleting(bulk ? selectedEntries : [menu.entry]);
          }}
          onNewFolder={() => setCreatingFolder(true)}
        />
      )}

      {renaming && (
        <TextInputDialog
          title="Rename"
          initialValue={renaming.name}
          confirmLabel="Rename"
          onCancel={() => setRenaming(null)}
          onConfirm={(value) => {
            const trimmed = value.trim();
            if (trimmed && trimmed !== renaming.name) {
              renameFile(joinPath(currentPath, renaming.name), trimmed);
            }
            setRenaming(null);
          }}
        />
      )}

      {creatingFolder && (
        <TextInputDialog
          title="New Folder"
          initialValue=""
          placeholder="Folder name"
          confirmLabel="Create"
          onCancel={() => setCreatingFolder(false)}
          onConfirm={(value) => {
            const trimmed = value.trim();
            if (trimmed) createFolder(trimmed);
            setCreatingFolder(false);
          }}
        />
      )}

      {deleting && (
        <ConfirmDialog
          title={deleting.length === 1 ? `Delete ${deleting[0].name}?` : `Delete ${deleting.length} items?`}
          message={
            deleting.some((d) => d.isDirectory)
              ? "This will delete the selected folder(s) and everything in them."
              : "This can't be undone."
          }
          onCancel={() => setDeleting(null)}
          onConfirm={() => {
            for (const d of deleting) deleteFile(joinPath(currentPath, d.name));
            setDeleting(null);
          }}
        />
      )}
    </div>
  );
}

function Banner({
  icon: Icon,
  tint,
  text,
  spin,
}: {
  icon: typeof Loader2;
  tint: "blue" | "emerald" | "orange";
  text: string;
  spin?: boolean;
}) {
  const classes = {
    blue: "bg-blue-500/10 text-blue-700 dark:text-blue-400",
    emerald: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
    orange: "bg-orange-500/10 text-orange-700 dark:text-orange-400",
  }[tint];
  return (
    <motion.div
      initial={{ opacity: 0, y: -4 }}
      animate={{ opacity: 1, y: 0 }}
      className={`mt-2 flex items-center gap-2 rounded-lg px-3 py-2 text-xs ${classes}`}
    >
      <Icon className={`h-3.5 w-3.5 ${spin ? "animate-spin" : ""}`} />
      {text}
    </motion.div>
  );
}

function FilePreviewPanel({
  selectedEntries,
  mediaSrc,
  isVideo,
  isAudio,
  loading,
  width,
  onResizeStart,
}: {
  selectedEntries: FileEntry[];
  mediaSrc: string | null;
  isVideo: boolean;
  isAudio: boolean;
  loading: boolean;
  width: number;
  onResizeStart: (e: React.MouseEvent) => void;
}) {
  const contentKey =
    selectedEntries.length === 0
      ? "empty"
      : selectedEntries.length === 1
        ? selectedEntries[0].name
        : `count-${selectedEntries.length}`;

  return (
    <motion.div
      initial={{ opacity: 0, x: 24 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ type: "spring", stiffness: 420, damping: 38 }}
      style={{ width }}
      className="relative flex h-full shrink-0 flex-col border-l border-black/5 bg-neutral-50 shadow-soft dark:border-white/10 dark:bg-neutral-950"
    >
      {/* Straddles the left edge so it's easy to grab without needing pixel-perfect aim — the
          hover highlight is on the wide outer strip (group), not the 1px line itself, which
          would only trigger if the cursor landed on that exact pixel. */}
      <div
        onMouseDown={onResizeStart}
        className="group/resize absolute -left-2 top-0 z-10 h-full w-4 cursor-col-resize select-none"
      >
        <div className="mx-auto h-full w-px bg-black/10 transition-colors group-hover/resize:bg-blue-500/60 dark:bg-white/10" />
      </div>

      <AnimatePresence mode="wait">
        <motion.div
          key={contentKey}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.15 }}
          className="flex h-full min-h-0 flex-col"
        >
          {selectedEntries.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center gap-2 px-6 text-center text-neutral-400 dark:text-neutral-500">
              <FileIcon className="h-8 w-8" />
              <p className="text-sm">Select a file to preview</p>
            </div>
          ) : selectedEntries.length > 1 ? (
            <MultiSelectSummary entries={selectedEntries} />
          ) : (
            <SingleFilePreview
              entry={selectedEntries[0]}
              mediaSrc={mediaSrc}
              isVideo={isVideo}
              isAudio={isAudio}
              loading={loading}
            />
          )}
        </motion.div>
      </AnimatePresence>
    </motion.div>
  );
}

function MultiSelectSummary({ entries }: { entries: FileEntry[] }) {
  const totalBytes = entries.reduce((sum, e) => sum + (e.isDirectory ? 0 : e.sizeBytes), 0);
  return (
    <div className="flex h-full flex-col items-center justify-center gap-1.5 px-6 text-center">
      <Files className="h-8 w-8 text-neutral-400 dark:text-neutral-500" strokeWidth={1.5} />
      <p className="text-sm font-medium text-neutral-700 dark:text-neutral-300">
        {entries.length} items selected
      </p>
      {totalBytes > 0 && <p className="text-xs text-neutral-400">{formatBytes(totalBytes)}</p>}
    </div>
  );
}

function SingleFilePreview({
  entry,
  mediaSrc,
  isVideo,
  isAudio,
  loading,
}: {
  entry: FileEntry;
  mediaSrc: string | null;
  isVideo: boolean;
  isAudio: boolean;
  loading: boolean;
}) {
  const { icon: Icon, className, kind } = fileTypeInfo(entry.name, entry.isDirectory);
  return (
    <div className="flex h-full min-h-0 flex-col px-5 py-6">
      <div className="mb-5 flex min-h-0 flex-1 items-center justify-center">
        {mediaSrc && isVideo ? (
          <video
            src={mediaSrc}
            controls
            className="h-full max-h-full w-full select-none rounded-xl border border-black/5 shadow-soft dark:border-white/10"
          />
        ) : mediaSrc && isAudio ? (
          <div className="flex h-full w-full flex-col items-center justify-center gap-6 rounded-xl border border-black/5 dark:border-white/10">
            <Icon className={`h-20 w-20 ${className}`} strokeWidth={1.25} />
            {/* eslint-disable-next-line jsx-a11y/media-has-caption -- audio-only, no track to caption */}
            <audio src={mediaSrc} controls className="w-full max-w-xs" />
          </div>
        ) : mediaSrc ? (
          <img
            src={mediaSrc}
            alt={entry.name}
            className="h-full max-h-full w-full select-none rounded-xl border border-black/5 object-contain shadow-soft dark:border-white/10"
          />
        ) : loading ? (
          <div className="flex h-full w-full items-center justify-center rounded-xl border border-black/5 dark:border-white/10">
            <Loader2 className="h-6 w-6 animate-spin text-neutral-400" />
          </div>
        ) : (
          <div className="flex h-full w-full items-center justify-center">
            <Icon className={`h-20 w-20 ${className}`} strokeWidth={1.25} />
          </div>
        )}
      </div>

      <div className="shrink-0">
        <h3 className="break-words text-[15px] font-semibold text-neutral-900 dark:text-neutral-100">
          {entry.name}
        </h3>
        <p className="mt-1 text-xs text-neutral-500 dark:text-neutral-400">
          {kind}
          {!entry.isDirectory && ` · ${formatBytes(entry.sizeBytes)}`}
        </p>

        <div className="mt-5 border-t border-black/5 pt-4 dark:border-white/10">
          <p className="text-[11px] font-medium uppercase tracking-wide text-neutral-400 dark:text-neutral-500">
            Modified
          </p>
          <p className="mt-0.5 text-[13px] text-neutral-700 dark:text-neutral-300">
            {formatFullDate(entry.modifiedAt)}
          </p>
        </div>
      </div>
    </div>
  );
}

function EntryIcon({ entry }: { entry: FileEntry }) {
  // Lucide icons default to a fixed 24x24 SVG unless given an explicit size class — `h-full
  // w-full` makes the icon fill whatever box its caller sizes (h-9 w-9 in grid view, h-4 w-4 in
  // list view) instead of always rendering at 24px regardless of that box, which was throwing
  // off centering in grid view and overflowing into the filename text in list view.
  if (!entry.isDirectory) return <FileIcon className="h-full w-full text-neutral-400" />;
  const { icon: Icon, className } = folderIconFor(entry.name);
  return <Icon className={`h-full w-full ${className}`} />;
}

function FileGridItem({
  entry,
  selected,
  cutPending,
  registerRef,
  onClick,
  onDoubleClick,
  onContextMenu,
}: {
  entry: FileEntry;
  selected: boolean;
  cutPending: boolean;
  registerRef: (el: HTMLElement | null) => void;
  onClick: (e: React.MouseEvent) => void;
  onDoubleClick: () => void;
  onContextMenu: (e: React.MouseEvent) => void;
}) {
  return (
    <button
      ref={registerRef}
      data-file-name={entry.name}
      onClick={(e) => {
        e.stopPropagation();
        onClick(e);
      }}
      onDoubleClick={onDoubleClick}
      onContextMenu={onContextMenu}
      className={`flex w-full min-w-0 select-none flex-col items-center gap-1.5 rounded-lg p-2 text-center transition-colors ${
        selected ? "bg-blue-500/20" : "hover:bg-black/[0.03] dark:hover:bg-white/[0.05]"
      } ${cutPending ? "opacity-50" : ""}`}
    >
      <div className="h-9 w-9 shrink-0">
        <EntryIcon entry={entry} />
      </div>
      <span className="line-clamp-2 h-[30px] w-full break-words text-xs text-neutral-700 dark:text-neutral-300">
        {entry.name}
      </span>
    </button>
  );
}

function SortHeaderCell({
  label,
  sortKey,
  sort,
  onSort,
  align = "left",
  resizeColumn,
  onResizeStart,
  onResizeDoubleClick,
}: {
  label: string;
  sortKey: SortKey;
  sort: SortState;
  onSort: (key: SortKey) => void;
  align?: "left" | "right";
  resizeColumn?: ColumnKey;
  onResizeStart?: (column: ColumnKey, e: React.MouseEvent) => void;
  onResizeDoubleClick?: (e: React.MouseEvent) => void;
}) {
  const active = sort.key === sortKey;
  return (
    // self-stretch overrides the header row's `items-center` just for this cell, so its own
    // height actually spans the full row — otherwise the cell shrinks to the button's content
    // height and the resize handle's h-full resolves against that instead of the row, making it
    // a sliver a few pixels tall instead of the whole header height.
    <div className="relative flex h-full items-center self-stretch">
      <button
        onClick={() => onSort(sortKey)}
        className={`flex w-full items-center gap-1 text-xs font-medium transition-colors ${
          align === "right" ? "justify-end" : "justify-start"
        } ${active ? "text-neutral-700 dark:text-neutral-200" : "text-neutral-500 dark:text-neutral-400"} hover:text-neutral-800 dark:hover:text-neutral-100`}
      >
        {label}
        {active ? (
          sort.direction === "asc" ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />
        ) : (
          <ChevronDown className="h-3 w-3 opacity-30" />
        )}
      </button>
      {resizeColumn && onResizeStart && (
        <div
          onMouseDown={(e) => onResizeStart(resizeColumn, e)}
          onDoubleClick={onResizeDoubleClick}
          className="group/resize absolute -right-2 top-0 z-10 h-full w-4 cursor-col-resize select-none"
        >
          <div className="mx-auto h-full w-px bg-black/10 transition-colors group-hover/resize:bg-blue-500/60 dark:bg-white/10" />
        </div>
      )}
    </div>
  );
}

function FileTableHeader({
  sort,
  onSort,
  allChecked,
  someChecked,
  onToggleAll,
  gridTemplateColumns,
  onColumnResizeStart,
  onNameResizeDoubleClick,
}: {
  sort: SortState;
  onSort: (key: SortKey) => void;
  allChecked: boolean;
  someChecked: boolean;
  onToggleAll: () => void;
  gridTemplateColumns: string;
  onColumnResizeStart: (column: ColumnKey, e: React.MouseEvent) => void;
  onNameResizeDoubleClick: (e: React.MouseEvent) => void;
}) {
  return (
    <div
      style={{ gridTemplateColumns }}
      // A fixed height (not padding-driven auto) so the row track actually has height to give
      // out — CSS Grid sizes an auto track from its items' own content first and only then
      // aligns them within it, so a cell's `self-stretch` has nothing to stretch into unless
      // the track itself is already tall. That's what was making the resize handles below only
      // a few pixels tall instead of spanning the visible header.
      className="sticky top-0 z-10 grid h-10 select-none items-center gap-2.5 rounded-t-2xl border-b border-black/[0.06] px-3 dark:border-white/10 bg-neutral-50 dark:bg-neutral-950"
    >
      <RowCheckbox checked={allChecked} indeterminate={someChecked} onChange={onToggleAll} />
      <SortHeaderCell
        label="Name"
        sortKey="name"
        sort={sort}
        onSort={onSort}
        resizeColumn="name"
        onResizeStart={onColumnResizeStart}
        onResizeDoubleClick={onNameResizeDoubleClick}
      />
      <SortHeaderCell
        label="Kind"
        sortKey="kind"
        sort={sort}
        onSort={onSort}
        resizeColumn="kind"
        onResizeStart={onColumnResizeStart}
      />
      <SortHeaderCell
        label="Modified"
        sortKey="modified"
        sort={sort}
        onSort={onSort}
        resizeColumn="modified"
        onResizeStart={onColumnResizeStart}
      />
      {/* No resize handle — it's the last content column, nothing to its right to negotiate
          space with. */}
      <SortHeaderCell label="Size" sortKey="size" sort={sort} onSort={onSort} align="right" />
      <span />
    </div>
  );
}

function RowCheckbox({
  checked,
  indeterminate,
  onChange,
}: {
  checked: boolean;
  indeterminate?: boolean;
  onChange: () => void;
}) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = !!indeterminate;
  }, [indeterminate]);

  return (
    <input
      ref={ref}
      type="checkbox"
      checked={checked}
      onChange={onChange}
      onClick={(e) => e.stopPropagation()}
      className="h-3.5 w-3.5 shrink-0 cursor-pointer rounded border-neutral-300 dark:border-neutral-600 text-blue-500 focus:ring-blue-500/40"
    />
  );
}

function FileTableRow({
  entry,
  selected,
  cutPending,
  registerRef,
  onClick,
  onDoubleClick,
  onToggleChecked,
  onContextMenu,
  onMenuButton,
  gridTemplateColumns,
}: {
  entry: FileEntry;
  selected: boolean;
  cutPending: boolean;
  registerRef: (el: HTMLElement | null) => void;
  onClick: (e: React.MouseEvent) => void;
  onDoubleClick: () => void;
  onToggleChecked: () => void;
  onContextMenu: (e: React.MouseEvent) => void;
  onMenuButton: (e: React.MouseEvent<HTMLButtonElement>) => void;
  gridTemplateColumns: string;
}) {
  const { icon: Icon, className, kind } = fileTypeInfo(entry.name, entry.isDirectory);

  return (
    <div
      ref={registerRef}
      data-file-name={entry.name}
      onClick={(e) => {
        e.stopPropagation();
        onClick(e);
      }}
      onDoubleClick={onDoubleClick}
      onContextMenu={onContextMenu}
      style={{ gridTemplateColumns }}
      className={`group grid cursor-default select-none items-center gap-2.5 px-3 py-2 transition-colors ${
        selected ? "bg-blue-500/[0.12]" : "hover:bg-black/[0.02] dark:hover:bg-white/[0.03]"
      } ${cutPending ? "opacity-50" : ""}`}
    >
      <RowCheckbox checked={selected} onChange={onToggleChecked} />
      <div className="flex min-w-0 items-center gap-2.5">
        <Icon className={`h-[18px] w-[18px] shrink-0 ${className}`} strokeWidth={1.75} />
        <span className="truncate text-[13px] text-neutral-800 dark:text-neutral-200">{entry.name}</span>
      </div>
      <span className="truncate text-xs text-neutral-500 dark:text-neutral-400">{kind}</span>
      <span className="truncate text-xs text-neutral-500 dark:text-neutral-400">
        {formatFullDate(entry.modifiedAt)}
      </span>
      <span className="text-right text-xs text-neutral-500 dark:text-neutral-400">
        {entry.isDirectory ? "—" : formatBytes(entry.sizeBytes)}
      </span>
      <button
        onClick={onMenuButton}
        className="flex h-6 w-6 items-center justify-center rounded-md text-neutral-400 opacity-0 transition-opacity hover:bg-black/5 dark:hover:bg-white/10 group-hover:opacity-100"
      >
        <MoreHorizontal className="h-4 w-4" />
      </button>
    </div>
  );
}

function ContextMenu({
  menu,
  hasClipboard,
  onClose,
  onRename,
  onDownload,
  onCut,
  onCopy,
  onPaste,
  onDelete,
  onNewFolder,
}: {
  menu: MenuState;
  hasClipboard: boolean;
  onClose: () => void;
  onRename: () => void;
  onDownload: () => void;
  onCut: () => void;
  onCopy: () => void;
  onPaste: () => void;
  onDelete: () => void;
  onNewFolder: () => void;
}) {
  const items = menu.entry
    ? [
        ...(menu.entry.isDirectory ? [] : [{ label: "Download", action: onDownload }]),
        { label: "Rename…", action: onRename },
        { label: "Cut", action: onCut },
        { label: "Copy", action: onCopy },
        ...(hasClipboard ? [{ label: "Paste", action: onPaste }] : []),
        { label: "Delete", action: onDelete, destructive: true },
      ]
    : [
        ...(hasClipboard ? [{ label: "Paste", action: onPaste }] : []),
        { label: "New Folder", action: onNewFolder },
      ];

  const menuRef = useRef<HTMLDivElement>(null);

  // A row's own onClick calls stopPropagation (so clicking it doesn't also trigger marquee/other
  // container handlers) — which meant the old "close on the root div's onClick" approach never
  // fired when the click landed on a row: the click never reached the root at all, so selecting
  // a different file while this menu was open changed the selection but left the menu stuck
  // open. A document-level listener sidesteps that entirely — React's stopPropagation only stops
  // propagation through React's own synthetic tree, not a listener attached directly to
  // `document`, so this reliably sees every click regardless of what any row's handler does.
  useEffect(() => {
    function handlePointerDown(e: MouseEvent) {
      if (!menuRef.current?.contains(e.target as Node)) onClose();
    }
    document.addEventListener("mousedown", handlePointerDown, true);
    return () => document.removeEventListener("mousedown", handlePointerDown, true);
  }, [onClose]);

  return (
    <div
      ref={menuRef}
      style={{ left: menu.x, top: menu.y }}
      onClick={(e) => e.stopPropagation()}
      className="fixed z-50 min-w-[160px] rounded-xl border border-black/10 dark:border-white/10 bg-white dark:bg-neutral-800 py-1 shadow-modal"
    >
      {items.map((item) => (
        <button
          key={item.label}
          onClick={() => {
            item.action();
            onClose();
          }}
          className={`block w-full px-3 py-1.5 text-left text-[13px] hover:bg-black/5 dark:hover:bg-white/10 transition-colors ${
            "destructive" in item && item.destructive ? "text-red-600 dark:text-red-400" : "text-neutral-800 dark:text-neutral-200"
          }`}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}

function TextInputDialog({
  title,
  initialValue,
  placeholder,
  confirmLabel,
  onCancel,
  onConfirm,
}: {
  title: string;
  initialValue: string;
  placeholder?: string;
  confirmLabel: string;
  onCancel: () => void;
  onConfirm: (value: string) => void;
}) {
  const [value, setValue] = useState(initialValue);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onCancel();
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onCancel]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 backdrop-blur-[2px]"
      onClick={onCancel}
    >
      <motion.div
        initial={{ opacity: 0, scale: 0.95, y: 4 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        transition={{ type: "spring", stiffness: 500, damping: 34 }}
        onClick={(e) => e.stopPropagation()}
        className="w-80 rounded-2xl border border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900 p-5 shadow-modal"
      >
        <h3 className="font-semibold text-neutral-900 dark:text-neutral-100">{title}</h3>
        <input
          autoFocus
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && onConfirm(value)}
          placeholder={placeholder}
          className="mt-3 w-full rounded-lg border border-black/10 dark:border-white/15 bg-transparent px-2.5 py-1.5 text-[13px] focus:outline-none focus:ring-2 focus:ring-blue-500/40"
        />
        <div className="mt-4 flex justify-end gap-2">
          <button
            onClick={onCancel}
            className="rounded-lg px-3.5 py-1.5 text-[13px] text-neutral-600 dark:text-neutral-300 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={() => onConfirm(value)}
            disabled={!value.trim()}
            className="rounded-lg bg-blue-500 px-3.5 py-1.5 text-[13px] font-medium text-white disabled:opacity-40 hover:bg-blue-600 transition-colors"
          >
            {confirmLabel}
          </button>
        </div>
      </motion.div>
    </div>
  );
}
