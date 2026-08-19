import { useEffect, useMemo, useState } from "react";
import { motion } from "framer-motion";
import {
  ChevronRight,
  FolderPlus,
  LayoutGrid,
  List,
  Upload,
  File as FileIcon,
  Loader2,
  CheckCircle2,
  AlertTriangle,
  Folder,
} from "lucide-react";
import { SectionHeader } from "./SectionHeader";
import { ConfirmDialog } from "./ContactDetail";
import { sectionMeta } from "../theme/sections";
import { folderIconFor } from "../theme/folderIcons";
import { formatBytes, fileToBase64 } from "../utils/formatBytes";
import { relativeTime } from "../utils/relativeTime";
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
  type FileEntry,
} from "../store/files";

interface MenuState {
  x: number;
  y: number;
  entry: FileEntry | null; // null = background menu
}

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
  } = useFilesStore();
  const deviceName = useConnectionStore((s) => s.deviceName);

  const [selected, setSelected] = useState<string | null>(null);
  const [menu, setMenu] = useState<MenuState | null>(null);
  const [renaming, setRenaming] = useState<FileEntry | null>(null);
  const [creatingFolder, setCreatingFolder] = useState(false);
  const [deleting, setDeleting] = useState<FileEntry | null>(null);
  const [dragOver, setDragOver] = useState(false);

  useEffect(() => {
    initFilesListeners();
  }, []);

  useEffect(() => {
    if (loaded && entries.length === 0 && currentPath === "") listFiles("");
  }, [loaded]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => setSelected(null), [currentPath]);

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
    <div className="flex h-full flex-col" onClick={() => setMenu(null)}>
      <SectionHeader section={sectionMeta("files")} subtitle={`Browsing ${deviceName ?? "device"}`} />

      <div className="px-4 pt-3">
        <div
          onDragOver={(e) => {
            e.preventDefault();
            setDragOver(true);
          }}
          onDragLeave={() => setDragOver(false)}
          onDrop={handleDrop}
          className={`flex h-[110px] flex-col items-center justify-center gap-1.5 rounded-xl border-[1.5px] border-dashed transition-colors ${
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
        <div className="flex rounded-md bg-black/[0.04] dark:bg-white/[0.06] p-0.5">
          <button
            onClick={() => setViewMode("grid")}
            className={`rounded p-1 ${viewMode === "grid" ? "bg-white dark:bg-neutral-700 shadow-sm" : "text-neutral-400"}`}
          >
            <LayoutGrid className="h-3.5 w-3.5" />
          </button>
          <button
            onClick={() => setViewMode("list")}
            className={`rounded p-1 ${viewMode === "list" ? "bg-white dark:bg-neutral-700 shadow-sm" : "text-neutral-400"}`}
          >
            <List className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>

      <div
        className="flex-1 overflow-y-auto"
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
            {entries.map((entry) => (
              <FileGridItem
                key={entry.name}
                entry={entry}
                selected={selected === entry.name}
                cutPending={clipboard?.operation === "cut" && clipboard.name === entry.name}
                onClick={() => setSelected(entry.name)}
                onDoubleClick={() => openEntry(entry)}
                onContextMenu={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  setSelected(entry.name);
                  setMenu({ x: e.clientX, y: e.clientY, entry });
                }}
              />
            ))}
          </div>
        ) : (
          <div>
            {entries.map((entry) => (
              <FileListRow
                key={entry.name}
                entry={entry}
                selected={selected === entry.name}
                cutPending={clipboard?.operation === "cut" && clipboard.name === entry.name}
                onClick={() => setSelected(entry.name)}
                onDoubleClick={() => openEntry(entry)}
                onContextMenu={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  setSelected(entry.name);
                  setMenu({ x: e.clientX, y: e.clientY, entry });
                }}
              />
            ))}
          </div>
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
          onDelete={() => menu.entry && setDeleting(menu.entry)}
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
          title={`Delete ${deleting.name}?`}
          message={
            deleting.isDirectory
              ? "This will delete the folder and everything in it."
              : "This can't be undone."
          }
          onCancel={() => setDeleting(null)}
          onConfirm={() => {
            deleteFile(joinPath(currentPath, deleting.name));
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
  onClick,
  onDoubleClick,
  onContextMenu,
}: {
  entry: FileEntry;
  selected: boolean;
  cutPending: boolean;
  onClick: () => void;
  onDoubleClick: () => void;
  onContextMenu: (e: React.MouseEvent) => void;
}) {
  return (
    <button
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
      onDoubleClick={onDoubleClick}
      onContextMenu={onContextMenu}
      className={`flex w-full min-w-0 flex-col items-center gap-1.5 rounded-lg p-2 text-center transition-colors ${
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

function FileListRow({
  entry,
  selected,
  cutPending,
  onClick,
  onDoubleClick,
  onContextMenu,
}: {
  entry: FileEntry;
  selected: boolean;
  cutPending: boolean;
  onClick: () => void;
  onDoubleClick: () => void;
  onContextMenu: (e: React.MouseEvent) => void;
}) {
  return (
    <div
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
      onDoubleClick={onDoubleClick}
      onContextMenu={onContextMenu}
      className={`flex cursor-default items-center gap-2.5 px-4 py-1.5 transition-colors ${
        selected ? "bg-blue-500/20" : "hover:bg-black/[0.02] dark:hover:bg-white/[0.03]"
      } ${cutPending ? "opacity-50" : ""}`}
    >
      <div className="h-4 w-4 shrink-0">
        <EntryIcon entry={entry} />
      </div>
      <span className="flex-1 truncate text-[13px] text-neutral-800 dark:text-neutral-200">{entry.name}</span>
      <span className="w-[70px] shrink-0 text-right text-xs text-neutral-400">
        {entry.isDirectory ? "" : formatBytes(entry.sizeBytes)}
      </span>
      <span className="w-[90px] shrink-0 text-right text-xs text-neutral-400">
        {relativeTime(entry.modifiedAt)}
      </span>
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

  return (
    <div
      style={{ left: menu.x, top: menu.y }}
      onClick={(e) => e.stopPropagation()}
      className="fixed z-50 min-w-[160px] rounded-lg border border-black/10 dark:border-white/10 bg-white dark:bg-neutral-800 py-1 shadow-lg"
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
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" onClick={onCancel}>
      <motion.div
        initial={{ opacity: 0, scale: 0.95 }}
        animate={{ opacity: 1, scale: 1 }}
        onClick={(e) => e.stopPropagation()}
        className="w-80 rounded-xl bg-white dark:bg-neutral-900 p-5 shadow-xl"
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
