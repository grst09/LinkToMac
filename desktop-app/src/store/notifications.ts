import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface NotificationAction {
  title: string;
  actionId: string;
}

export interface AppNotification {
  id: string;
  packageName: string;
  appName: string;
  title: string;
  text: string;
  subText?: string | null;
  category?: string | null;
  postedAt: number;
  actions: NotificationAction[];
  iconBase64?: string | null;
  // Android's FLAG_ONGOING_EVENT/FLAG_NO_CLEAR (e.g. a car-key connection status) — the OS won't let
  // it be dismissed remotely, so the UI shouldn't offer to.
  ongoing: boolean;
}

interface NotificationsState {
  notifications: AppNotification[];
  loaded: boolean;
  errorMessage: string | null;
}

export const useNotificationsStore = create<NotificationsState>(() => ({
  notifications: [],
  loaded: false,
  errorMessage: null,
}));

function showTransientError(message: string) {
  useNotificationsStore.setState({ errorMessage: message });
  setTimeout(() => useNotificationsStore.setState({ errorMessage: null }), 4000);
}

/** Every notifications.* command below goes through this instead of calling `invoke` directly —
 *  without it, a rejected command (most commonly "no active connection", e.g. clicking Sync with
 *  nothing paired) was an unhandled promise rejection with zero user-facing feedback: the button
 *  looked clickable but visibly did nothing. Same fix as `invokeFiles` in store/files.ts. */
async function invokeNotifications<T>(command: string, args?: Record<string, unknown>): Promise<T | undefined> {
  try {
    return await invoke<T>(command, args);
  } catch (e) {
    showTransientError(typeof e === "string" ? e : "Something went wrong — check the connection");
    return undefined;
  }
}

export async function dismissNotification(id: string) {
  // Optimistic — matches the backend's own "remove immediately, then tell the phone" order.
  useNotificationsStore.setState((s) => ({
    notifications: s.notifications.filter((n) => n.id !== id),
  }));
  await invokeNotifications("dismiss_notification", { id });
}

/** Clears every dismissible notification at once — same per-id `notification.dismiss` the backend
 *  sends for a single dismiss, just for all of them. `ongoing` notifications (a car-key connection
 *  status, etc.) are left alone: Android refuses to let a NotificationListenerService cancel those,
 *  so optimistically clearing them here would just have them reappear on the next sync. Optimistic
 *  for the ones it does clear, same ordering as the single-dismiss case. */
export async function dismissAllNotifications() {
  const dismissible = useNotificationsStore.getState().notifications.filter((n) => !n.ongoing);
  if (dismissible.length === 0) return;
  useNotificationsStore.setState((s) => ({ notifications: s.notifications.filter((n) => n.ongoing) }));
  await invokeNotifications("dismiss_all_notifications");
}

/** Asks the phone for a fresh full list of whatever's actually showing right now — recovers from
 *  a posted/removed event missed while disconnected, which the live push-only
 *  `notification-posted`/`notification-removed` listeners below have no way to catch up on by
 *  themselves. The response replaces the list outright (see the "notifications-synced" listener),
 *  same full-snapshot-replace shape as Notes' own `refreshNotes`. */
export async function refreshNotifications() {
  await invokeNotifications("refresh_notifications");
}

let initialized = false;

export function initNotificationListeners() {
  if (initialized) return;
  initialized = true;

  invoke<AppNotification[]>("list_notifications").then((notifications) => {
    useNotificationsStore.setState({ notifications, loaded: true });
  });

  listen<AppNotification>("notification-posted", (event) => {
    useNotificationsStore.setState((s) => ({
      notifications: [event.payload, ...s.notifications.filter((n) => n.id !== event.payload.id)],
    }));
  });

  listen<{ id: string }>("notification-removed", (event) => {
    useNotificationsStore.setState((s) => ({
      notifications: s.notifications.filter((n) => n.id !== event.payload.id),
    }));
  });

  // Full-snapshot replace — response to refreshNotifications(), not an incremental update like
  // the two listeners above.
  listen<AppNotification[]>("notifications-synced", (event) => {
    useNotificationsStore.setState({ notifications: event.payload });
  });
}
