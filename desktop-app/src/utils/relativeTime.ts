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
