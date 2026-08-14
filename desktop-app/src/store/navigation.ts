import { create } from "zustand";

/** Cross-section "pending intent" mailbox, ported from ConnectionServer.swift's
 *  `pendingMessageAddress` — Contacts' Message action sets this; App.tsx watches it to switch
 *  the sidebar to Messages, and MessagesView watches it (on mount AND on change, matching the
 *  Swift app's dual-hook — see its comment) to open an existing thread or start composing a new
 *  one, then clears it so the "event" doesn't re-fire. */
interface NavigationState {
  pendingMessageAddress: string | null;
}

export const useNavigationStore = create<NavigationState>(() => ({
  pendingMessageAddress: null,
}));

export function requestMessageTo(address: string) {
  useNavigationStore.setState({ pendingMessageAddress: address });
}

export function clearPendingMessageAddress() {
  useNavigationStore.setState({ pendingMessageAddress: null });
}
