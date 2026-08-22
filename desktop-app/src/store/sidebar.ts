import { create } from "zustand";

const STORAGE_KEY = "linktomac:sidebar-collapsed";

interface SidebarState {
  collapsed: boolean;
}

export const useSidebarStore = create<SidebarState>(() => ({
  collapsed: localStorage.getItem(STORAGE_KEY) === "1",
}));

export function toggleSidebarCollapsed() {
  const collapsed = !useSidebarStore.getState().collapsed;
  localStorage.setItem(STORAGE_KEY, collapsed ? "1" : "0");
  useSidebarStore.setState({ collapsed });
}
