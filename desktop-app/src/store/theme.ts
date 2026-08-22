import { create } from "zustand";

export type ThemePreference = "light" | "dark" | "system";

const STORAGE_KEY = "linktomac:theme";

interface ThemeState {
  preference: ThemePreference;
  resolved: "light" | "dark";
}

function systemPrefersDark() {
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}

function resolve(preference: ThemePreference): "light" | "dark" {
  return preference === "system" ? (systemPrefersDark() ? "dark" : "light") : preference;
}

function readStoredPreference(): ThemePreference {
  const stored = localStorage.getItem(STORAGE_KEY);
  return stored === "light" || stored === "dark" || stored === "system" ? stored : "system";
}

const initialPreference = readStoredPreference();

export const useThemeStore = create<ThemeState>(() => ({
  preference: initialPreference,
  resolved: resolve(initialPreference),
}));

function apply(resolved: "light" | "dark") {
  document.documentElement.classList.toggle("dark", resolved === "dark");
  // Without this, :root's static `color-scheme: light dark` leaves the browser free to pick
  // UA-default (OS-driven) colors for anything without an explicit author color — e.g. a white
  // `bg-white` pill with no explicit text class renders invisible white-on-white the moment the
  // OS is dark but the user has explicitly picked Light here. Pinning color-scheme to the
  // resolved theme keeps UA defaults in sync with our own `.dark` class.
  document.documentElement.style.colorScheme = resolved;
  // Native title-bar sync — best-effort, the in-app theme is correct either way.
  import("@tauri-apps/api/window")
    .then(({ getCurrentWindow }) => getCurrentWindow().setTheme(resolved))
    .catch(() => {});
}

/** Call once at app startup. Applies the persisted preference (index.html's inline script
 *  already applied the `.dark` class synchronously pre-paint — this just brings the Tauri
 *  window's native theme and the live matchMedia listener in sync with it) and wires a
 *  listener so "system" keeps tracking OS changes while the app is open. */
export function initTheme() {
  apply(useThemeStore.getState().resolved);

  const media = window.matchMedia("(prefers-color-scheme: dark)");
  const onChange = () => {
    if (useThemeStore.getState().preference !== "system") return;
    const resolved = systemPrefersDark() ? "dark" : "light";
    useThemeStore.setState({ resolved });
    apply(resolved);
  };
  media.addEventListener("change", onChange);
}

export function setThemePreference(preference: ThemePreference) {
  localStorage.setItem(STORAGE_KEY, preference);
  const resolved = resolve(preference);
  useThemeStore.setState({ preference, resolved });
  apply(resolved);
}
