import { create } from "zustand";
import type { SectionId } from "../theme/sections";

/** Cross-section "pending intent" mailbox, ported from ConnectionServer.swift's
 *  `pendingMessageAddress` — Contacts' Message action sets this; App.tsx watches it to switch
 *  the sidebar to Messages, and MessagesView watches it (on mount AND on change, matching the
 *  Swift app's dual-hook — see its comment) to open an existing thread or start composing a new
 *  one, then clears it so the "event" doesn't re-fire. */
interface NavigationState {
  pendingMessageAddress: string | null;
  /** Set by any "jump to another section" action (e.g. This Device's Quick Actions) — App.tsx
   *  watches this and switches the sidebar selection, then clears it. */
  pendingSection: SectionId | null;
}

export const useNavigationStore = create<NavigationState>(() => ({
  pendingMessageAddress: null,
  pendingSection: null,
}));

export function requestMessageTo(address: string) {
  useNavigationStore.setState({ pendingMessageAddress: address });
}

export function clearPendingMessageAddress() {
  useNavigationStore.setState({ pendingMessageAddress: null });
}

export function requestSection(id: SectionId) {
  useNavigationStore.setState({ pendingSection: id });
}

export function clearPendingSection() {
  useNavigationStore.setState({ pendingSection: null });
}
