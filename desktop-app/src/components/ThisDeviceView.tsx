import { useCallback, useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { AnimatePresence, motion } from "framer-motion";
import { CheckCircle2, QrCode, X } from "lucide-react";
import QRCode from "qrcode";
import { SectionHeader } from "./SectionHeader";
import { BatteryIndicator } from "./BatteryIndicator";
import { sectionMeta } from "../theme/sections";
import { useConnectionStore, type DeviceStatus } from "../store/connection";

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

  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [devices, setDevices] = useState<PairedDeviceSummary[]>([]);

  const refreshDevices = useCallback(() => {
    invoke<PairedDeviceSummary[]>("list_paired_devices")
      .then(setDevices)
      .catch((e) => setError(String(e)));
  }, []);

  useEffect(() => {
    refreshDevices();
  }, [refreshDevices, revision]);

  async function beginPairing() {
    setError(null);
    try {
      const payload = await invoke<PairingQrPayload>("begin_pairing");
      const dataUrl = await QRCode.toDataURL(JSON.stringify(payload), {
        width: 220,
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

  return (
    <div className="flex h-full flex-col">
      <SectionHeader section={sectionMeta("device")} subtitle={deviceName ?? undefined} />

      <div className="flex-1 overflow-y-auto p-6">
        <StatusBanner connected={status === "connected"} deviceName={deviceName} deviceStatus={deviceStatus} />

        <div className="mt-6">
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-neutral-500 dark:text-neutral-400">
            Pair a new device
          </h2>
          <AnimatePresence mode="wait">
            {qrDataUrl ? (
              <motion.div
                key="qr"
                initial={{ opacity: 0, scale: 0.95 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.95 }}
                className="inline-flex flex-col items-start gap-3 rounded-xl border border-black/5 dark:border-white/10 p-4"
              >
                <img src={qrDataUrl} alt="Pairing QR code" className="rounded-lg" />
                <p className="text-xs text-neutral-500 dark:text-neutral-400">
                  Scan this with the LinkToMac app on your phone.
                </p>
                <button
                  onClick={() => setQrDataUrl(null)}
                  className="text-xs font-medium text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200"
                >
                  Cancel
                </button>
              </motion.div>
            ) : (
              <motion.button
                key="button"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                onClick={beginPairing}
                className="inline-flex items-center gap-2 rounded-lg bg-emerald-500 px-3.5 py-2 text-[13px] font-medium text-white hover:bg-emerald-600 transition-colors"
              >
                <QrCode className="h-4 w-4" />
                Pair New Device
              </motion.button>
            )}
          </AnimatePresence>
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
                {devices.map((d) => (
                  <motion.li
                    key={d.id}
                    layout
                    initial={{ opacity: 0, y: -4 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0, x: -8 }}
                    className="flex items-center gap-2.5 rounded-lg border border-black/5 dark:border-white/10 px-3 py-2"
                  >
                    <span
                      className={`h-2 w-2 shrink-0 rounded-full ${d.is_active ? "bg-emerald-500" : "bg-neutral-300 dark:bg-neutral-600"}`}
                    />
                    <span className="flex-1 truncate text-[13px] text-neutral-800 dark:text-neutral-200">
                      {d.device_name}
                    </span>
                    <button
                      onClick={() => forget(d.id)}
                      title="Forget this device"
                      className="text-neutral-400 hover:text-red-500 transition-colors"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  </motion.li>
                ))}
              </AnimatePresence>
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}

function StatusBanner({
  connected,
  deviceName,
  deviceStatus,
}: {
  connected: boolean;
  deviceName: string | null;
  deviceStatus: DeviceStatus | null;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 4 }}
      animate={{ opacity: 1, y: 0 }}
      className={`flex items-center gap-3 rounded-xl p-4 ${
        connected
          ? "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400"
          : "bg-black/[0.03] dark:bg-white/[0.05] text-neutral-600 dark:text-neutral-400"
      }`}
    >
      {connected ? <CheckCircle2 className="h-5 w-5" /> : <QrCode className="h-5 w-5" />}
      <div className="flex-1">
        <p className="text-[13px] font-medium">
          {connected ? `${deviceName} is connected` : "Waiting to pair"}
        </p>
        <p className="text-xs opacity-80">
          {connected
            ? "Connected and syncing."
            : "Pair a device below, or reconnect an already-paired phone by opening LinkToMac on it."}
        </p>
      </div>
      {connected && deviceStatus && <BatteryIndicator status={deviceStatus} className="opacity-90" />}
    </motion.div>
  );
}
