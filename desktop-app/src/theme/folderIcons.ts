import {
  Folder,
  Image,
  Download,
  Music,
  Bell,
  AlarmClock,
  Mic,
  BookOpen,
  AudioLines,
  BellRing,
  Film,
  FileText,
  Settings,
  Terminal,
  type LucideIcon,
} from "lucide-react";

/** Well-known Android storage folder names -> icon + color, ported from FilesView.swift's
 *  folder icon map. Matched case-insensitively; anything else falls back to a plain folder. */
const FOLDER_MAP: Record<string, { icon: LucideIcon; className: string }> = {
  dcim: { icon: Image, className: "text-orange-500" },
  pictures: { icon: Image, className: "text-orange-500" },
  photos: { icon: Image, className: "text-orange-500" },
  download: { icon: Download, className: "text-emerald-500" },
  downloads: { icon: Download, className: "text-emerald-500" },
  music: { icon: Music, className: "text-pink-500" },
  ringtones: { icon: Bell, className: "text-purple-500" },
  alarms: { icon: AlarmClock, className: "text-red-500" },
  podcasts: { icon: Mic, className: "text-purple-500" },
  audiobooks: { icon: BookOpen, className: "text-amber-700" },
  recordings: { icon: AudioLines, className: "text-red-500" },
  notifications: { icon: BellRing, className: "text-orange-500" },
  movies: { icon: Film, className: "text-red-500" },
  video: { icon: Film, className: "text-red-500" },
  videos: { icon: Film, className: "text-red-500" },
  documents: { icon: FileText, className: "text-blue-500" },
  android: { icon: Settings, className: "text-neutral-500" },
  log: { icon: Terminal, className: "text-neutral-500" },
  logs: { icon: Terminal, className: "text-neutral-500" },
};

export function folderIconFor(name: string): { icon: LucideIcon; className: string } {
  return FOLDER_MAP[name.toLowerCase()] ?? { icon: Folder, className: "text-blue-500" };
}
