/** Compact relative-time string ("44s", "5m", "3h", "2d") — no date library needed for
 *  something this small. `epochMillis` matches the wire protocol's convention (Double ms). */
export function relativeTime(epochMillis: number): string {
  const diffSeconds = Math.max(0, (Date.now() - epochMillis) / 1000);
  if (diffSeconds < 60) return `${Math.floor(diffSeconds)}s`;
  const diffMinutes = diffSeconds / 60;
  if (diffMinutes < 60) return `${Math.floor(diffMinutes)}m`;
  const diffHours = diffMinutes / 60;
  if (diffHours < 24) return `${Math.floor(diffHours)}h`;
  const diffDays = diffHours / 24;
  return `${Math.floor(diffDays)}d`;
}

/** Clock-face time ("10:20 AM") for a message bubble's timestamp — shared by the SMS and
 *  WhatsApp conversation views, whose message timestamps arrive in different units (SMS: epoch
 *  ms; WhatsApp/Baileys: epoch seconds) — callers normalize to ms before calling this. */
export function formatClockTime(epochMillis: number): string {
  return new Date(epochMillis).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}
