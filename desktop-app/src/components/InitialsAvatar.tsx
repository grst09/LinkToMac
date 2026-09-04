import { User } from "lucide-react";
import { avatarColorClass, initials } from "../theme/avatarColor";

/** A raw phone number or short code (no saved contact name resolved) has no letters at all —
 *  `initials()` on one of those just takes its first character, showing a stray "+" or a lone
 *  digit instead of anything meaningful. A generic person glyph reads as "unknown contact"
 *  instead of looking like a rendering glitch. */
function looksLikeAPersonsName(name: string): boolean {
  return /[a-zA-Z]/.test(name);
}

/** Colored-circle initials avatar — ported from InitialsAvatarView.swift, shared by
 *  Contacts/Messages (and Notifications uses the same color-hash via avatarColor.ts, just
 *  rendered as a rounded-square instead of a circle since that's an app-icon slot). */
export function InitialsAvatar({ name, diameter = 36 }: { name: string; diameter?: number }) {
  return (
    <div
      className={`flex shrink-0 items-center justify-center rounded-full font-bold text-white ${avatarColorClass(name)}`}
      style={{ width: diameter, height: diameter, fontSize: diameter * 0.4 }}
    >
      {looksLikeAPersonsName(name) ? initials(name) : <User style={{ width: diameter * 0.55, height: diameter * 0.55 }} />}
    </div>
  );
}
