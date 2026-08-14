import { avatarColorClass, initials } from "../theme/avatarColor";

/** Colored-circle initials avatar — ported from InitialsAvatarView.swift, shared by
 *  Contacts/Messages (and Notifications uses the same color-hash via avatarColor.ts, just
 *  rendered as a rounded-square instead of a circle since that's an app-icon slot). */
export function InitialsAvatar({ name, diameter = 36 }: { name: string; diameter?: number }) {
  return (
    <div
      className={`flex shrink-0 items-center justify-center rounded-full font-bold text-white ${avatarColorClass(name)}`}
      style={{ width: diameter, height: diameter, fontSize: diameter * 0.4 }}
    >
      {initials(name)}
    </div>
  );
}
