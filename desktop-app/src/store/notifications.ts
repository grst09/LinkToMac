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
}

interface NotificationsState {
  notifications: AppNotification[];
  loaded: boolean;
}

export const useNotificationsStore = create<NotificationsState>(() => ({
  notifications: [],
  loaded: false,
}));

export async function dismissNotification(id: string) {
  // Optimistic — matches the backend's own "remove immediately, then tell the phone" order.
  useNotificationsStore.setState((s) => ({
    notifications: s.notifications.filter((n) => n.id !== id),
  }));
  await invoke("dismiss_notification", { id });
}

/** Clears every notification at once — both the phone's copy (a `notification.dismiss` per id,
 *  same as `dismissNotification`) and every native Notification Center banner in one call, via
 *  `notify::remove_all` on the Rust side. Optimistic, same ordering as the single-dismiss case. */
export async function dismissAllNotifications() {
  if (useNotificationsStore.getState().notifications.length === 0) return;
  useNotificationsStore.setState({ notifications: [] });
  await invoke("dismiss_all_notifications");
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
}
