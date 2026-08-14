import { motion } from "framer-motion";
import { Smartphone, Wifi } from "lucide-react";
import { useConnectionStore } from "../store/connection";
import { BatteryIndicator } from "./BatteryIndicator";

/** Compact device-status card pinned above the sidebar nav list. Ported from
 *  SidebarDeviceCard.swift — connection dot color/text, device name, wifi glyph when
 *  connected, battery when known. The disconnect/reconnect toggle from the old app isn't
 *  wired up yet (the Rust server doesn't support runtime start/stop of the listener yet —
 *  see docs/PLAN.md). */
export function DeviceCard() {
  const { status, deviceName, deviceStatus } = useConnectionStore();
  const connected = status === "connected";

  return (
    <div className="mx-3 mt-3 mb-2 rounded-xl bg-black/[0.03] dark:bg-white/[0.06] p-2.5">
      <div className="flex items-center gap-2.5">
        <Smartphone className="h-5 w-5 text-neutral-400 dark:text-neutral-500 shrink-0" strokeWidth={1.75} />
        <div className="min-w-0 flex-1">
          <p className="truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
            {deviceName ?? "No device"}
          </p>
          <div className="flex items-center gap-1.5">
            <motion.span
              className={`h-1.5 w-1.5 rounded-full ${connected ? "bg-emerald-500" : "bg-neutral-400 dark:bg-neutral-600"}`}
              animate={connected ? { scale: [1, 1.3, 1] } : { scale: 1 }}
              transition={connected ? { duration: 1.8, repeat: Infinity, ease: "easeInOut" } : undefined}
            />
            <span className="text-[11px] text-neutral-500 dark:text-neutral-400">
              {connected ? "Connected" : "Waiting to pair"}
            </span>
            {connected && <Wifi className="h-3 w-3 text-neutral-400 dark:text-neutral-500" />}
          </div>
        </div>
      </div>
      {connected && deviceStatus && (
        <div className="mt-1.5 pl-[30px]">
          <BatteryIndicator status={deviceStatus} />
        </div>
      )}
    </div>
  );
}
