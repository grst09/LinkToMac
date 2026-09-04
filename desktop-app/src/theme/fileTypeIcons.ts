import {
  File,
  FileText,
  FileSpreadsheet,
  Presentation,
  FileCode,
  FileImage,
  FileAudio,
  FileVideo,
  FileArchive,
  type LucideIcon,
} from "lucide-react";
import { folderIconFor } from "./folderIcons";

interface FileTypeInfo {
  icon: LucideIcon;
  className: string;
  kind: string;
}

const CODE_EXTENSIONS = new Set([
  "js", "jsx", "ts", "tsx", "py", "rb", "go", "rs", "c", "cpp", "h", "hpp",
  "java", "kt", "swift", "php", "html", "css", "scss", "json", "sh", "yaml",
  "yml", "sql", "xml",
]);

const EXTENSION_MAP: Record<string, { icon: LucideIcon; className: string; kind: string }> = {
  pdf: { icon: FileText, className: "text-red-500", kind: "PDF Document" },
  doc: { icon: FileText, className: "text-blue-500", kind: "Word Document" },
  docx: { icon: FileText, className: "text-blue-500", kind: "Word Document" },
  rtf: { icon: FileText, className: "text-blue-500", kind: "Rich Text Document" },
  txt: { icon: FileText, className: "text-neutral-500", kind: "Text File" },
  md: { icon: FileText, className: "text-neutral-500", kind: "Markdown File" },
  xls: { icon: FileSpreadsheet, className: "text-emerald-500", kind: "Excel Spreadsheet" },
  xlsx: { icon: FileSpreadsheet, className: "text-emerald-500", kind: "Excel Spreadsheet" },
  csv: { icon: FileSpreadsheet, className: "text-emerald-500", kind: "CSV File" },
  numbers: { icon: FileSpreadsheet, className: "text-emerald-500", kind: "Numbers Spreadsheet" },
  ppt: { icon: Presentation, className: "text-orange-500", kind: "PowerPoint Presentation" },
  pptx: { icon: Presentation, className: "text-orange-500", kind: "PowerPoint Presentation" },
  key: { icon: Presentation, className: "text-orange-500", kind: "Keynote Presentation" },
  png: { icon: FileImage, className: "text-pink-500", kind: "Image" },
  jpg: { icon: FileImage, className: "text-pink-500", kind: "Image" },
  jpeg: { icon: FileImage, className: "text-pink-500", kind: "Image" },
  gif: { icon: FileImage, className: "text-pink-500", kind: "Image" },
  webp: { icon: FileImage, className: "text-pink-500", kind: "Image" },
  heic: { icon: FileImage, className: "text-pink-500", kind: "Image" },
  svg: { icon: FileImage, className: "text-pink-500", kind: "Image" },
  bmp: { icon: FileImage, className: "text-pink-500", kind: "Image" },
  mp3: { icon: FileAudio, className: "text-fuchsia-500", kind: "Audio File" },
  wav: { icon: FileAudio, className: "text-fuchsia-500", kind: "Audio File" },
  aac: { icon: FileAudio, className: "text-fuchsia-500", kind: "Audio File" },
  flac: { icon: FileAudio, className: "text-fuchsia-500", kind: "Audio File" },
  m4a: { icon: FileAudio, className: "text-fuchsia-500", kind: "Audio File" },
  ogg: { icon: FileAudio, className: "text-fuchsia-500", kind: "Audio File" },
  mp4: { icon: FileVideo, className: "text-rose-500", kind: "Video File" },
  mov: { icon: FileVideo, className: "text-rose-500", kind: "Video File" },
  avi: { icon: FileVideo, className: "text-rose-500", kind: "Video File" },
  mkv: { icon: FileVideo, className: "text-rose-500", kind: "Video File" },
  webm: { icon: FileVideo, className: "text-rose-500", kind: "Video File" },
  zip: { icon: FileArchive, className: "text-amber-600", kind: "Archive" },
  rar: { icon: FileArchive, className: "text-amber-600", kind: "Archive" },
  "7z": { icon: FileArchive, className: "text-amber-600", kind: "Archive" },
  tar: { icon: FileArchive, className: "text-amber-600", kind: "Archive" },
  gz: { icon: FileArchive, className: "text-amber-600", kind: "Archive" },
};

/** Icon + color + human-readable kind for a file or folder, keyed off its extension —
 *  mirrors folderIconFor's well-known-folder table but for the "Kind" column in list view. */
export function fileTypeInfo(name: string, isDirectory: boolean): FileTypeInfo {
  if (isDirectory) {
    const { icon, className } = folderIconFor(name);
    return { icon, className, kind: "Folder" };
  }

  const dot = name.lastIndexOf(".");
  const ext = dot === -1 ? "" : name.slice(dot + 1).toLowerCase();

  const known = EXTENSION_MAP[ext];
  if (known) return known;

  if (CODE_EXTENSIONS.has(ext)) {
    return { icon: FileCode, className: "text-purple-500", kind: `${ext.toUpperCase()} File` };
  }

  if (!ext) return { icon: File, className: "text-neutral-400", kind: "File" };
  return { icon: File, className: "text-neutral-400", kind: `${ext.toUpperCase()} File` };
}

const PREVIEWABLE_IMAGE_EXTENSIONS = new Set([
  "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "svg",
]);

/** Whether the Files preview panel should fetch this file's bytes and render it as an <img> —
 *  everything else just gets its fileTypeInfo icon shown at a larger size. */
export function isPreviewableImage(name: string): boolean {
  const dot = name.lastIndexOf(".");
  if (dot === -1) return false;
  return PREVIEWABLE_IMAGE_EXTENSIONS.has(name.slice(dot + 1).toLowerCase());
}

// Rendered as a real preview by the backend's Quick Look thumbnailer (see
// quicklook::thumbnail on the Rust side) rather than sent straight through like an image —
// same panel, same <img>, the bytes just come from a generated thumbnail instead of the file
// itself.
const PREVIEWABLE_DOCUMENT_EXTENSIONS = new Set([
  "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "rtf", "pages", "key", "numbers",
]);

function isPreviewableDocument(name: string): boolean {
  const dot = name.lastIndexOf(".");
  if (dot === -1) return false;
  return PREVIEWABLE_DOCUMENT_EXTENSIONS.has(name.slice(dot + 1).toLowerCase());
}

// Formats WebKit's <video> element can actually play — sent through as raw bytes (no
// thumbnailing) so the preview panel can render a real, scrubbable <video controls>.
const PREVIEWABLE_VIDEO_EXTENSIONS = new Set(["mp4", "mov", "m4v", "webm"]);

export function isPreviewableVideo(name: string): boolean {
  const dot = name.lastIndexOf(".");
  if (dot === -1) return false;
  return PREVIEWABLE_VIDEO_EXTENSIONS.has(name.slice(dot + 1).toLowerCase());
}

// Formats WebKit's <audio> element can actually play — Safari/WebKit has never supported Ogg
// (Vorbis/Opus), so that's deliberately left out here the same way it is for video.
const PREVIEWABLE_AUDIO_EXTENSIONS = new Set(["mp3", "m4a", "aac", "wav", "flac"]);

export function isPreviewableAudio(name: string): boolean {
  const dot = name.lastIndexOf(".");
  if (dot === -1) return false;
  return PREVIEWABLE_AUDIO_EXTENSIONS.has(name.slice(dot + 1).toLowerCase());
}

/** Whether the Files preview panel should fetch bytes for this file at all — images, videos,
 *  and audio pass straight through, documents get rendered to a thumbnail first; everything
 *  else just shows its fileTypeInfo icon with no network round trip. */
export function isPreviewable(name: string): boolean {
  return (
    isPreviewableImage(name) || isPreviewableDocument(name) || isPreviewableVideo(name) || isPreviewableAudio(name)
  );
}
