import {
  Bell,
  MessageCircle,
  Image,
  Folder,
  Users,
  MonitorSmartphone,
  Smartphone,
  Settings as SettingsIcon,
  type LucideIcon,
} from "lucide-react";

export type SectionId =
  | "notifications"
  | "messages"
  | "photos"
  | "files"
  | "contacts"
  | "mirroring"
  | "device"
  | "settings";

export interface SectionMeta {
  id: SectionId;
  label: string;
  icon: LucideIcon;
  /** Matches the accent colors already established in the old SwiftUI app's SectionHeaderView
   *  usages (orange notifications, blue messages/files, purple photos/contacts) — carried
   *  forward rather than reinvented. Screen Mirroring / This Device / Settings didn't have an
   *  established color there, so those are new picks. */
  accent: {
    text: string;
    bg: string;
    ring: string;
  };
}

// Tailwind's scanner needs literal class strings (not template-interpolated ones) to include
// them in the build, so each accent is spelled out in full rather than generated from a hue name.
const ACCENTS = {
  orange: {
    text: "text-orange-500 dark:text-orange-400",
    bg: "bg-orange-500/10 dark:bg-orange-400/10",
    ring: "ring-orange-500/20",
  },
  blue: {
    text: "text-blue-500 dark:text-blue-400",
    bg: "bg-blue-500/10 dark:bg-blue-400/10",
    ring: "ring-blue-500/20",
  },
  purple: {
    text: "text-purple-500 dark:text-purple-400",
    bg: "bg-purple-500/10 dark:bg-purple-400/10",
    ring: "ring-purple-500/20",
  },
  teal: {
    text: "text-teal-500 dark:text-teal-400",
    bg: "bg-teal-500/10 dark:bg-teal-400/10",
    ring: "ring-teal-500/20",
  },
  emerald: {
    text: "text-emerald-500 dark:text-emerald-400",
    bg: "bg-emerald-500/10 dark:bg-emerald-400/10",
    ring: "ring-emerald-500/20",
  },
  slate: {
    text: "text-slate-500 dark:text-slate-400",
    bg: "bg-slate-500/10 dark:bg-slate-400/10",
    ring: "ring-slate-500/20",
  },
} as const;

export const SECTIONS: SectionMeta[] = [
  { id: "notifications", label: "Notifications", icon: Bell, accent: ACCENTS.orange },
  { id: "messages", label: "Messages", icon: MessageCircle, accent: ACCENTS.blue },
  { id: "photos", label: "Photos", icon: Image, accent: ACCENTS.purple },
  { id: "files", label: "Files", icon: Folder, accent: ACCENTS.blue },
  { id: "contacts", label: "Contacts", icon: Users, accent: ACCENTS.purple },
  { id: "mirroring", label: "Screen Mirroring", icon: MonitorSmartphone, accent: ACCENTS.teal },
  { id: "device", label: "This Device", icon: Smartphone, accent: ACCENTS.emerald },
];

export const SETTINGS_SECTION: SectionMeta = {
  id: "settings",
  label: "Settings",
  icon: SettingsIcon,
  accent: ACCENTS.slate,
};

export const ALL_SECTIONS: SectionMeta[] = [...SECTIONS, SETTINGS_SECTION];

export function sectionMeta(id: SectionId): SectionMeta {
  return ALL_SECTIONS.find((s) => s.id === id) ?? SETTINGS_SECTION;
}
