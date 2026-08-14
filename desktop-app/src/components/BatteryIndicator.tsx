import { Battery, BatteryCharging, BatteryFull, BatteryLow, BatteryMedium, BatteryWarning } from "lucide-react";
import type { DeviceStatus } from "../store/connection";

/** Battery glyph + percent, ported from BatteryIcon.swift's thresholds. Unlike the old app
 *  (which deliberately avoided a charging-specific SF Symbol since an unrecognized name
 *  renders blank), Lucide has a real `BatteryCharging` icon, so charging state gets both a
 *  distinct icon and the green color the old app used. */
export function BatteryIndicator({ status, className = "" }: { status: DeviceStatus; className?: string }) {
  const Icon = status.isCharging ? BatteryCharging : iconForPercent(status.batteryPercent);
  return (
    <div className={`flex items-center gap-1 ${status.isCharging ? "text-emerald-500" : "text-neutral-500 dark:text-neutral-400"} ${className}`}>
      <Icon className="h-3.5 w-3.5" />
      <span className="text-[11px]">{status.batteryPercent}%</span>
    </div>
  );
}

function iconForPercent(percent: number) {
  if (percent < 13) return BatteryWarning;
  if (percent < 38) return BatteryLow;
  if (percent < 63) return BatteryMedium;
  if (percent < 88) return Battery;
  return BatteryFull;
}
