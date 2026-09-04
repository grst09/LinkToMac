/** Clusters consecutive items from the same sender into "bursts" — e.g. several texts sent
 *  back-to-back by the same person render as one visually grouped stack (sender label + one
 *  timestamp for the group) instead of repeating that chrome on every single bubble. Shared by
 *  both the SMS and WhatsApp conversation views so their message-list styling stays consistent. */
export function groupIntoBursts<T>(items: T[], sameSender: (a: T, b: T) => boolean): T[][] {
  const bursts: T[][] = [];
  for (const item of items) {
    const current = bursts[bursts.length - 1];
    if (current && sameSender(current[current.length - 1], item)) {
      current.push(item);
    } else {
      bursts.push([item]);
    }
  }
  return bursts;
}
