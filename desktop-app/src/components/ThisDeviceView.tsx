import { useCallback, useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { AnimatePresence, motion } from "framer-motion";
import {
  Battery,
  BatteryCharging,
  CheckCircle2,
  ChevronRight,
  Clipboard as ClipboardIcon,
  Link2,
  QrCode,
  Smartphone,
  Unlink2,
  Wifi,
  X,
  XCircle,
  type LucideIcon,
} from "lucide-react";
import QRCode from "qrcode";
import { SectionHeader } from "./SectionHeader";
import { StatCard } from "./StatCard";
import { AnimatedListRow } from "./AnimatedListRow";
import { ToggleRow } from "./SettingsView";
import { sectionMeta } from "../theme/sections";
import { useConnectionStore } from "../store/connection";
import { requestSection } from "../store/navigation";

interface PairingQrPayload {
  host: string;
  port: number;
  pairingToken: string;
  macDeviceId: string;
}

interface PairedDeviceSummary {
  id: string;
  device_name: string;
  paired_at: string;
  is_active: boolean;
}

/** "This Device" section: live connection status, pairing QR flow, and the list of
 *  previously-paired phones with a way to forget one. Combines what the old Swift app split
 *  across DeviceStatusView.swift (status) and MenuBarView.swift (pairing + device list) — this
 *  app doesn't have a menu-bar tray popover yet (later-phase work), so both live here for now. */
export function ThisDeviceView() {
  const { status, deviceName, deviceStatus } = useConnectionStore();
  const revision = useConnectionStore((s) => s.revision);
  const connected = status === "connected";

  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [pairingStatus, setPairingStatus] = useState<"waiting" | "success" | "failed">("waiting");
  const [error, setError] = useState<string | null>(null);
  const [devices, setDevices] = useState<PairedDeviceSummary[]>([]);
  const [discoveryEnabled, setDiscoveryEnabled] = useState(true);
  const closeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    invoke<boolean>("get_discovery_enabled").then(setDiscoveryEnabled);
  }, []);

  // Optimistic, like every other toggle in this app (see SettingsView's ToggleRow usages) —
  // reverted if the backend call fails.
  async function toggleDiscovery(enabled: boolean) {
    setDiscoveryEnabled(enabled);
    try {
      await invoke("set_discovery_enabled", { enabled });
    } catch (e) {
      setDiscoveryEnabled(!enabled);
      setError(String(e));
    }
  }

  const refreshDevices = useCallback(() => {
    invoke<PairedDeviceSummary[]>("list_paired_devices")
      .then(setDevices)
      .catch((e) => setError(String(e)));
  }, []);

  // Once `connection` store flips to connected (the "paired" event) while the modal is open,
  // show a brief success state before dismissing — an instant close would flash past too fast
  // for the checkmark to actually register.
  useEffect(() => {
    if (connected && qrDataUrl) {
      setPairingStatus("success");
      closeTimerRef.current = setTimeout(() => setQrDataUrl(null), 1200);
      return () => {
        if (closeTimerRef.current) clearTimeout(closeTimerRef.current);
      };
    }
  }, [connected, qrDataUrl]);

  // A rejected handshake (stale/expired/reused pairing token) — shown as a failure state and,
  // unlike success, does NOT auto-close: the user needs to see it went wrong and decide whether
  // to retry (a fresh "Pair New Device" click) or cancel.
  useEffect(() => {
    if (!qrDataUrl) return;
    const unlisten = listen("pairing-failed", () => setPairingStatus("failed"));
    return () => {
      unlisten.then((fn) => fn());
    };
  }, [qrDataUrl]);

  useEffect(() => {
    if (!qrDataUrl) return;
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setQrDataUrl(null);
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [qrDataUrl]);

  useEffect(() => {
    refreshDevices();
  }, [refreshDevices, revision]);

  async function beginPairing() {
    setError(null);
    setPairingStatus("waiting");
    try {
      // Pairing a new device needs an actual reachable listening socket — a QR code pointing at
      // a closed port would just fail to connect with no clear reason why. Deliberately starting
      // this flow is exactly the kind of "I want to be reachable right now" action discovery-off
      // exists to gate, so turn it back on rather than leaving the user to guess.
      if (!discoveryEnabled) await toggleDiscovery(true);
      const payload = await invoke<PairingQrPayload>("begin_pairing");
      const dataUrl = await QRCode.toDataURL(JSON.stringify(payload), {
        width: 280,
        margin: 1,
      });
      setQrDataUrl(dataUrl);
    } catch (e) {
      setError(String(e));
    }
  }

  async function forget(id: string) {
    try {
      await invoke("forget_device", { id });
      refreshDevices();
    } catch (e) {
      setError(String(e));
    }
  }

  /** Severs the live connection without forgetting the pairing — the "disconnected" event this
   *  triggers (see net/server.rs's `disconnect_device`) updates `useConnectionStore` on its own,
   *  same as any other disconnect, so no local state update is needed here beyond the error path. */
  async function disconnect(id: string) {
    try {
      await invoke("disconnect_device", { id });
    } catch (e) {
      setError(String(e));
    }
  }

  return (
    <div className="flex h-full flex-col">
      <SectionHeader section={sectionMeta("device")} subtitle={deviceName ?? undefined} />

      <div className="flex-1 overflow-y-auto p-6">
        {connected && (
          <div className="mb-4 grid grid-cols-3 gap-3">
            <StatCard
              icon={deviceStatus?.isCharging ? BatteryCharging : Battery}
              label="Battery"
              value={deviceStatus ? `${deviceStatus.batteryPercent}%` : "—"}
              accent={
                deviceStatus?.isCharging
                  ? { text: "text-emerald-500 dark:text-emerald-400", bg: "bg-emerald-500/10 dark:bg-emerald-400/10" }
                  : { text: "text-neutral-500 dark:text-neutral-400", bg: "bg-black/[0.04] dark:bg-white/[0.06]" }
              }
            />
            <StatCard
              icon={Wifi}
              label="Connection"
              value="Wi-Fi"
              accent={{ text: "text-blue-500 dark:text-blue-400", bg: "bg-blue-500/10 dark:bg-blue-400/10" }}
            />
            <StatCard
              icon={CheckCircle2}
              label="Status"
              value="Paired"
              accent={{ text: "text-emerald-500 dark:text-emerald-400", bg: "bg-emerald-500/10 dark:bg-emerald-400/10" }}
            />
          </div>
        )}

        <HeroCard connected={connected} deviceName={deviceName} discoveryEnabled={discoveryEnabled} />

        <div className="mt-6">
          <ToggleRow
            label="Discoverable"
            detail={
              discoveryEnabled
                ? "Your Mac advertises itself on the network and accepts reconnections. Turn off when you don't want it reachable."
                : "Off — your Mac isn't advertising itself and won't accept new connections, even from an already-paired phone."
            }
            checked={discoveryEnabled}
            onChange={toggleDiscovery}
          />
        </div>

        <div className="mt-8">
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
            Quick Actions
          </h2>
          <div className="grid grid-cols-2 gap-3">
            <QuickActionCard
              icon={ClipboardIcon}
              tint="cyan"
              label="Clipboard"
              detail="View sync history"
              onClick={() => requestSection("clipboard")}
            />
            <QuickActionCard
              icon={QrCode}
              tint="emerald"
              label="Pair New Device"
              detail="Scan a QR code"
              onClick={beginPairing}
            />
          </div>

          {error && <p className="mt-2 text-xs text-red-500">{error}</p>}
        </div>

        <div className="mt-8">
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
            Paired devices
          </h2>
          {devices.length === 0 ? (
            <p className="text-sm text-neutral-500 dark:text-neutral-400">No paired devices yet.</p>
          ) : (
            <ul className="space-y-1.5">
              <AnimatePresence>
                {devices.map((d, i) => (
                  <PairedDeviceRow
                    key={d.id}
                    device={d}
                    index={i}
                    onForget={() => forget(d.id)}
                    onDisconnect={() => disconnect(d.id)}
                  />
                ))}
              </AnimatePresence>
            </ul>
          )}
        </div>
      </div>

      <AnimatePresence>
        {qrDataUrl && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => setQrDataUrl(null)}
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-8"
          >
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 8 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 8 }}
              onClick={(e) => e.stopPropagation()}
              className="flex w-[360px] flex-col items-center gap-5 rounded-2xl border border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900 p-8 shadow-soft-hover"
            >
              <div className="text-center">
                <p className="text-lg font-bold text-neutral-900 dark:text-neutral-100">Pair New Device</p>
                <p className="mt-1 text-sm text-neutral-500 dark:text-neutral-400">
                  Scan this with the LinkToMac app on your phone.
                </p>
              </div>

              <img src={qrDataUrl} alt="Pairing QR code" className="h-[280px] w-[280px] rounded-lg" />

              <PairingStatusLine status={pairingStatus} />

              <button
                onClick={() => setQrDataUrl(null)}
                className="rounded-lg bg-black/[0.04] px-4 py-1.5 text-sm font-medium text-neutral-600 hover:bg-black/[0.07] dark:bg-white/10 dark:text-neutral-300 dark:hover:bg-white/15 transition-colors"
              >
                Cancel
              </button>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

function PairingStatusLine({ status }: { status: "waiting" | "success" | "failed" }) {
  if (status === "success") {
    return (
      <p className="flex items-center gap-1.5 text-sm font-medium text-emerald-600 dark:text-emerald-400">
        <CheckCircle2 className="h-4 w-4" />
        Paired!
      </p>
    );
  }
  if (status === "failed") {
    return (
      <p className="flex items-center gap-1.5 text-sm font-medium text-red-500 dark:text-red-400">
        <XCircle className="h-4 w-4" />
        Pairing failed — try again
      </p>
    );
  }
  return (
    <p className="flex items-center gap-1.5 text-sm font-medium text-neutral-500 dark:text-neutral-400">
      <span className="h-1.5 w-1.5 rounded-full bg-neutral-400 animate-pulse" />
      Waiting to pair…
    </p>
  );
}

function HeroCard({
  connected,
  deviceName,
  discoveryEnabled,
}: {
  connected: boolean;
  deviceName: string | null;
  discoveryEnabled: boolean;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 4 }}
      animate={{ opacity: 1, y: 0 }}
      className={`flex flex-col items-center gap-4 rounded-2xl border p-6 shadow-soft ${
        connected
          ? "border-emerald-500/30 bg-emerald-500/[0.03] dark:bg-emerald-500/[0.06]"
          : "border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900"
      }`}
    >
      <span
        className={`flex h-16 w-16 items-center justify-center rounded-full ${
          connected ? "bg-emerald-500/15" : "bg-black/[0.04] dark:bg-white/[0.06]"
        }`}
      >
        <Smartphone
          className={`h-7 w-7 ${connected ? "text-emerald-600 dark:text-emerald-400" : "text-neutral-400"}`}
          strokeWidth={1.75}
        />
      </span>

      <div className="flex flex-col items-center gap-0.5 text-center">
        <p className="text-lg font-bold text-neutral-900 dark:text-neutral-100">
          {connected ? (deviceName ?? "Connected device") : "No device"}
        </p>
        <p
          className={`flex items-center gap-1.5 text-[13px] font-medium ${
            connected ? "text-emerald-600 dark:text-emerald-400" : "text-neutral-500 dark:text-neutral-400"
          }`}
        >
          <span className={`h-1.5 w-1.5 rounded-full ${connected ? "bg-emerald-500" : "bg-neutral-400"}`} />
          {connected ? "Connected" : discoveryEnabled ? "Waiting to pair" : "Discovery off"}
        </p>
        {!connected && (
          <p className="mt-1 max-w-xs text-xs text-neutral-500 dark:text-neutral-400">
            {discoveryEnabled
              ? "Pair a device below, or reconnect an already-paired phone by opening LinkToMac on it."
              : "Your Mac isn't reachable right now — turn Discoverable back on below to pair or reconnect."}
          </p>
        )}
      </div>

    </motion.div>
  );
}

function QuickActionCard({
  icon: Icon,
  tint,
  label,
  detail,
  onClick,
}: {
  icon: LucideIcon;
  tint: "cyan" | "emerald";
  label: string;
  detail: string;
  onClick: () => void;
}) {
  const tintClasses =
    tint === "cyan"
      ? "bg-cyan-500/10 text-cyan-600 dark:text-cyan-400"
      : "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400";
  return (
    <button
      onClick={onClick}
      className="flex flex-col items-center gap-2 rounded-2xl border border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900 py-5 text-center shadow-soft transition-all hover:-translate-y-0.5 hover:shadow-soft-hover"
    >
      <span className={`flex h-10 w-10 items-center justify-center rounded-full ${tintClasses}`}>
        <Icon className="h-4.5 w-4.5" />
      </span>
      <span className="text-[13px] font-semibold text-neutral-900 dark:text-neutral-100">{label}</span>
      <span className="text-xs text-neutral-500 dark:text-neutral-400">{detail}</span>
    </button>
  );
}

function PairedDeviceRow({
  device,
  index,
  onForget,
  onDisconnect,
}: {
  device: PairedDeviceSummary;
  index: number;
  onForget: () => void;
  onDisconnect: () => void;
}) {
  // Guards against a second click queuing a second close frame on the same connection before
  // the `disconnected` event's revision bump has re-fetched `is_active: false` — the Rust side
  // now tears the connection down cleanly either way (see net/server.rs), but there's no reason
  // to send a redundant close, and disabling makes the click feel like it did something instead
  // of appearing to no-op until the list refreshes.
  const [disconnecting, setDisconnecting] = useState(false);

  useEffect(() => {
    if (!device.is_active) setDisconnecting(false);
  }, [device.is_active]);

  return (
    <AnimatedListRow
      index={index}
      transition={{ duration: 0.18, ease: "easeOut" }}
      className="flex items-center gap-3 rounded-2xl border border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900 px-3 py-2.5 shadow-soft"
    >
      <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-black/[0.04] dark:bg-white/[0.06]">
        <Smartphone className="h-4 w-4 text-neutral-400" strokeWidth={1.75} />
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-[13px] font-medium text-neutral-800 dark:text-neutral-200">
          {device.device_name}
        </p>
        <p className="text-xs text-neutral-400 dark:text-neutral-500">Android</p>
      </div>
      {device.is_active ? (
        <button
          onClick={() => {
            setDisconnecting(true);
            onDisconnect();
          }}
          disabled={disconnecting}
          title="Disconnect this device"
          className="flex items-center gap-1 rounded-full bg-emerald-500/10 px-2 py-0.5 text-[11px] font-medium text-emerald-600 hover:bg-emerald-500/20 disabled:opacity-60 dark:text-emerald-400 transition-colors"
        >
          <Link2 className="h-3 w-3" />
          {disconnecting ? "Disconnecting…" : "Connected"}
        </button>
      ) : (
        <span className="flex items-center gap-1 rounded-full bg-red-500/10 px-2 py-0.5 text-[11px] font-medium text-red-600 dark:text-red-400">
          <Unlink2 className="h-3 w-3" />
          Disconnected
        </span>
      )}
      <button
        onClick={onForget}
        title="Forget this device"
        className="text-neutral-400 hover:text-red-500 transition-colors"
      >
        <X className="h-4 w-4" />
      </button>
      <ChevronRight className="h-4 w-4 text-neutral-300 dark:text-neutral-600" />
    </AnimatedListRow>
  );
}
