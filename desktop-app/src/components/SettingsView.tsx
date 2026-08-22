import { useEffect, useState } from "react";
import { motion } from "framer-motion";
import { Trash2, Sun, Moon, Monitor } from "lucide-react";
import { SectionHeader } from "./SectionHeader";
import { SegmentedControl } from "./SegmentedControl";
import { ConfirmDialog } from "./ContactDetail";
import { sectionMeta } from "../theme/sections";
import { formatBytes } from "../utils/formatBytes";
import { useThemeStore, setThemePreference, type ThemePreference } from "../store/theme";
import {
  useSettingsStore,
  loadSettings,
  updateSettings,
  setLaunchAtLogin,
  clearDownloadedFiles,
  type MirrorQuality,
} from "../store/settings";

const THEME_OPTIONS: { value: ThemePreference; label: string; icon: typeof Sun }[] = [
  { value: "light", label: "Light", icon: Sun },
  { value: "dark", label: "Dark", icon: Moon },
  { value: "system", label: "System", icon: Monitor },
];

const MIRROR_QUALITY_OPTIONS: { value: MirrorQuality; label: string; detail: string }[] = [
  { value: "performance", label: "Performance", detail: "Lower resolution, ~12fps — smoothest if the picture lags" },
  { value: "balanced", label: "Balanced", detail: "Default — good picture without falling behind" },
  { value: "quality", label: "Quality", detail: "Higher resolution, ~30fps — best picture, may lag on slower Macs" },
];

export function SettingsView() {
  const { settings, launchAtLogin, storageInfo, appVersion, loaded } = useSettingsStore();
  const themePreference = useThemeStore((s) => s.preference);
  const [confirmingClear, setConfirmingClear] = useState(false);

  useEffect(() => {
    loadSettings();
  }, []);

  if (!loaded || !settings) return null;

  return (
    <div className="flex h-full flex-col">
      <SectionHeader section={sectionMeta("settings")} />
      <div className="flex-1 overflow-y-auto p-6">
        <SettingsGroup title="Appearance">
          <div className="flex items-center justify-between rounded-2xl border border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900 px-3.5 py-3 shadow-soft">
            <div>
              <p className="text-[13px] font-medium text-neutral-900 dark:text-neutral-100">Theme</p>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                System matches your Mac's Light/Dark setting automatically.
              </p>
            </div>
            <SegmentedControl
              layoutId="theme-preference"
              value={themePreference}
              onChange={setThemePreference}
              options={THEME_OPTIONS}
            />
          </div>
        </SettingsGroup>

        <SettingsGroup title="Notifications">
          <ToggleRow
            label="Show notification banners"
            detail="Native pop-up banners for mirrored notifications. They still appear in the Notifications list either way."
            checked={settings.showNotificationBanners}
            onChange={(checked) => updateSettings({ showNotificationBanners: checked })}
          />
        </SettingsGroup>

        <SettingsGroup title="Clipboard">
          <ToggleRow
            label="Sync clipboard between devices"
            detail="Copies on either device are pushed to the other automatically."
            checked={settings.clipboardSyncEnabled}
            onChange={(checked) => updateSettings({ clipboardSyncEnabled: checked })}
          />
        </SettingsGroup>

        <SettingsGroup title="Screen Mirroring">
          <div className="divide-y divide-black/5 dark:divide-white/10 rounded-2xl border border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900 shadow-soft">
            {MIRROR_QUALITY_OPTIONS.map((option) => (
              <button
                key={option.value}
                onClick={() => updateSettings({ mirrorQuality: option.value })}
                className="flex w-full items-start gap-3 px-3.5 py-3 text-left transition-colors hover:bg-black/[0.03] dark:hover:bg-white/[0.05]"
              >
                <span
                  className={`mt-0.5 h-4 w-4 shrink-0 rounded-full border-2 ${
                    settings.mirrorQuality === option.value
                      ? "border-teal-500 bg-teal-500"
                      : "border-neutral-300 dark:border-neutral-600"
                  }`}
                />
                <span>
                  <span className="block text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                    {option.label}
                  </span>
                  <span className="block text-xs text-neutral-500 dark:text-neutral-400">{option.detail}</span>
                </span>
              </button>
            ))}
          </div>
        </SettingsGroup>

        <SettingsGroup title="File Transfers">
          <div className="flex items-center justify-between rounded-2xl border border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900 px-3.5 py-3 shadow-soft">
            <div>
              <p className="text-[13px] font-medium text-neutral-900 dark:text-neutral-100">Max file size</p>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                Files larger than this are rejected before sending to your phone.
              </p>
            </div>
            <div className="flex items-center gap-1.5">
              <input
                type="number"
                min={1}
                max={50}
                value={settings.maxTransferMb}
                onChange={(e) => {
                  const mb = Math.max(1, Math.min(50, Number(e.target.value) || 1));
                  updateSettings({ maxTransferMb: mb });
                }}
                className="w-16 rounded-lg border border-black/10 dark:border-white/15 bg-transparent px-2 py-1 text-right text-[13px] focus:outline-none focus:ring-2 focus:ring-blue-500/40"
              />
              <span className="text-xs text-neutral-500 dark:text-neutral-400">MB</span>
            </div>
          </div>
        </SettingsGroup>

        <SettingsGroup title="Startup">
          <ToggleRow
            label="Launch at login"
            detail="Start LinkToMac automatically when you log in."
            checked={launchAtLogin}
            onChange={(checked) => setLaunchAtLogin(checked)}
          />
        </SettingsGroup>

        <SettingsGroup title="Storage">
          <div className="rounded-2xl border border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900 shadow-soft">
            <InfoRow
              label="Downloaded files"
              value={storageInfo ? `${storageInfo.file_count} file${storageInfo.file_count === 1 ? "" : "s"}` : "—"}
            />
            <div className="h-px bg-black/5 dark:bg-white/10" />
            <InfoRow label="Storage used" value={storageInfo ? formatBytes(storageInfo.total_bytes) : "—"} />
            {storageInfo && storageInfo.file_count > 0 && (
              <>
                <div className="h-px bg-black/5 dark:bg-white/10" />
                <button
                  onClick={() => setConfirmingClear(true)}
                  className="flex w-full items-center gap-2 px-3.5 py-3 text-left text-[13px] font-medium text-red-600 dark:text-red-400 transition-colors hover:bg-red-500/5"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                  Clear all downloaded files
                </button>
              </>
            )}
          </div>
        </SettingsGroup>

        <SettingsGroup title="About">
          <div className="rounded-2xl border border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900 shadow-soft">
            <InfoRow label="App" value="LinkToMac" />
            <div className="h-px bg-black/5 dark:bg-white/10" />
            <InfoRow label="Version" value={appVersion ?? "—"} />
          </div>
        </SettingsGroup>
      </div>

      {confirmingClear && (
        <ConfirmDialog
          title="Clear all downloaded files?"
          message="This deletes everything in your Downloads/LinkToMac folder. This can't be undone."
          onCancel={() => setConfirmingClear(false)}
          onConfirm={async () => {
            await clearDownloadedFiles();
            setConfirmingClear(false);
          }}
        />
      )}
    </div>
  );
}

function SettingsGroup({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mb-6">
      <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
        {title}
      </h2>
      {children}
    </div>
  );
}

function ToggleRow({
  label,
  detail,
  checked,
  onChange,
}: {
  label: string;
  detail: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-2xl border border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900 px-3.5 py-3 shadow-soft">
      <div className="min-w-0">
        <p className="text-[13px] font-medium text-neutral-900 dark:text-neutral-100">{label}</p>
        <p className="text-xs text-neutral-500 dark:text-neutral-400">{detail}</p>
      </div>
      <button
        onClick={() => onChange(!checked)}
        role="switch"
        aria-checked={checked}
        className={`inline-flex h-6 w-10 shrink-0 items-center rounded-full transition-colors ${
          checked ? "bg-blue-500" : "bg-black/15 dark:bg-white/20"
        }`}
      >
        <motion.span
          layout
          transition={{ type: "spring", stiffness: 700, damping: 32 }}
          className="inline-block h-5 w-5 rounded-full bg-white shadow"
          style={{ marginLeft: checked ? 18 : 2 }}
        />
      </button>
    </div>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between px-3.5 py-3">
      <span className="text-[13px] text-neutral-600 dark:text-neutral-300">{label}</span>
      <span className="text-[13px] font-medium text-neutral-900 dark:text-neutral-100">{value}</span>
    </div>
  );
}
