// Colored-initial avatar fallback, shared wherever there's no photo/icon to show (notifications
// without an icon, contacts, message threads) — ported from InitialsAvatarView.swift's
// color-hash approach so the same name always gets the same color across the app.

const PALETTE = [
  "bg-purple-500",
  "bg-orange-500",
  "bg-teal-500",
  "bg-emerald-500",
  "bg-red-500",
  "bg-blue-500",
  "bg-pink-500",
  "bg-indigo-500",
  "bg-amber-600",
] as const;

export function avatarColorClass(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = (hash << 5) - hash + name.charCodeAt(i);
    hash |= 0;
  }
  return PALETTE[Math.abs(hash) % PALETTE.length];
}

export function initials(name: string): string {
  const letters = name
    .split(" ")
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]);
  return (letters.join("") || "?").toUpperCase();
}
