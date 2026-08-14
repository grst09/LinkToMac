// Colored-initial avatar fallback, shared wherever there's no photo/icon to show (notifications
// without an icon, contacts, message threads) — ported from InitialsAvatarView.swift's
// color-hash approach so the same name always gets the same color across the app.

const PALETTE = [
  { class: "bg-purple-500", hex: "#a855f7" },
  { class: "bg-orange-500", hex: "#f97316" },
  { class: "bg-teal-500", hex: "#14b8a6" },
  { class: "bg-emerald-500", hex: "#10b981" },
  { class: "bg-red-500", hex: "#ef4444" },
  { class: "bg-blue-500", hex: "#3b82f6" },
  { class: "bg-pink-500", hex: "#ec4899" },
  { class: "bg-indigo-500", hex: "#6366f1" },
  { class: "bg-amber-600", hex: "#d97706" },
] as const;

function paletteEntry(name: string) {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = (hash << 5) - hash + name.charCodeAt(i);
    hash |= 0;
  }
  return PALETTE[Math.abs(hash) % PALETTE.length];
}

export function avatarColorClass(name: string): string {
  return paletteEntry(name).class;
}

/** For per-contact gradient washes (the hero-card detail background) where a fixed Tailwind
 *  class can't express a dynamic, per-instance color — used as an inline-style value, not a
 *  class name, so it's exempt from the "Tailwind needs literal classes" rule elsewhere in this
 *  file (see docs/PLAN.md's Phase B notes on that gotcha). */
export function avatarColorHex(name: string): string {
  return paletteEntry(name).hex;
}

export function initials(name: string): string {
  const letters = name
    .split(" ")
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]);
  return (letters.join("") || "?").toUpperCase();
}
