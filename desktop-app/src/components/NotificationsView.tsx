import { useEffect } from "react";
import { AnimatePresence } from "framer-motion";
import { X } from "lucide-react";
import { SectionHeader } from "./SectionHeader";
import { AnimatedListRow } from "./AnimatedListRow";
import { sectionMeta } from "../theme/sections";
import { avatarColorClass, initials } from "../theme/avatarColor";
import { relativeTime } from "../utils/relativeTime";
import {
  dismissNotification,
  initNotificationListeners,
  useNotificationsStore,
  type AppNotification,
} from "../store/notifications";

export function NotificationsView() {
  const { notifications, loaded } = useNotificationsStore();

  useEffect(() => {
    initNotificationListeners();
  }, []);

  return (
    <div className="flex h-full flex-col">
      <SectionHeader
        section={sectionMeta("notifications")}
        subtitle={`${notifications.length} notification${notifications.length === 1 ? "" : "s"}`}
      />
      <div className="flex-1 overflow-y-auto p-4">
        {!loaded ? null : notifications.length === 0 ? (
          <div className="flex h-full items-center justify-center text-sm text-neutral-400 dark:text-neutral-500">
            No notifications yet.
          </div>
        ) : (
          <ul className="space-y-2">
            <AnimatePresence initial={false}>
              {notifications.map((n, i) => (
                <NotificationRow key={n.id} notification={n} index={i} />
              ))}
            </AnimatePresence>
          </ul>
        )}
      </div>
    </div>
  );
}

function NotificationRow({ notification, index }: { notification: AppNotification; index: number }) {
  return (
    <AnimatedListRow
      index={index}
      className="flex gap-3 rounded-2xl border border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900 p-3 shadow-soft"
    >
      <NotificationIcon notification={notification} />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-xs font-semibold text-neutral-500 dark:text-neutral-400">
            {notification.appName}
          </span>
          <span className="flex-1" />
          <span className="text-xs text-neutral-400 dark:text-neutral-500">
            {relativeTime(notification.postedAt)}
          </span>
          <button
            onClick={() => dismissNotification(notification.id)}
            className="text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-200 transition-colors"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
        <p className="truncate text-[13px] font-semibold text-neutral-900 dark:text-neutral-100">
          {notification.title}
        </p>
        <p className="line-clamp-2 text-[13px] text-neutral-500 dark:text-neutral-400">
          {notification.text}
        </p>
        {notification.actions.length > 0 && (
          <div className="mt-1.5 flex gap-1.5">
            {notification.actions.slice(0, 2).map((action, i) => (
              <span
                key={action.actionId}
                className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${
                  i === 0
                    ? "bg-orange-500/15 text-orange-600 dark:text-orange-400"
                    : "bg-black/5 dark:bg-white/10 text-neutral-600 dark:text-neutral-300"
                }`}
              >
                {action.title}
              </span>
            ))}
          </div>
        )}
      </div>
    </AnimatedListRow>
  );
}

function NotificationIcon({ notification }: { notification: AppNotification }) {
  if (notification.iconBase64) {
    return (
      <img
        src={`data:image/png;base64,${notification.iconBase64}`}
        alt=""
        className="h-10 w-10 shrink-0 rounded-[10px] object-cover"
      />
    );
  }
  return (
    <div
      className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-[10px] text-sm font-bold text-white ${avatarColorClass(notification.appName)}`}
    >
      {initials(notification.appName)}
    </div>
  );
}
