import { motion } from "framer-motion";
import type { LucideIcon } from "lucide-react";

interface SegmentedControlOption<T extends string> {
  value: T;
  label: string;
  icon?: LucideIcon;
}

interface SegmentedControlProps<T extends string> {
  value: T;
  onChange: (value: T) => void;
  options: SegmentedControlOption<T>[];
  /** Unique per on-screen instance — framer-motion's shared-element `layoutId` animation only
   *  makes sense scoped to one control at a time. */
  layoutId: string;
  /** Options fill the container width evenly instead of sizing to content — for a pill switcher
   *  that should span its parent (e.g. a list-column sub-tab bar) rather than sit inline. */
  fullWidth?: boolean;
  /** Icon-only, no label — for a compact view-mode toggle. Falls back to the option's `label`
   *  as the button's accessible/hover title. */
  iconOnly?: boolean;
}

/** Sliding-pill control — same spring/layoutId technique as Sidebar's active-nav indicator. */
export function SegmentedControl<T extends string>({
  value,
  onChange,
  options,
  layoutId,
  fullWidth,
  iconOnly,
}: SegmentedControlProps<T>) {
  return (
    <div
      className={`${fullWidth ? "flex" : "inline-flex"} items-center gap-0.5 rounded-lg bg-black/[0.04] dark:bg-white/[0.06] p-0.5`}
    >
      {options.map((option) => {
        const active = option.value === value;
        const Icon = option.icon;
        return (
          <button
            key={option.value}
            onClick={() => onChange(option.value)}
            title={iconOnly ? option.label : undefined}
            className={`relative flex items-center justify-center gap-1.5 rounded-md font-medium transition-colors ${
              fullWidth ? "flex-1" : ""
            } ${iconOnly ? "p-1" : "px-3 py-1.5 text-[13px]"} ${
              active
                ? "text-neutral-900 dark:text-neutral-100"
                : "text-neutral-500 dark:text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-200"
            }`}
          >
            {active && (
              <motion.span
                layoutId={layoutId}
                className="absolute inset-0 rounded-md bg-white dark:bg-neutral-700 shadow-sm"
                transition={{ type: "spring", stiffness: 500, damping: 40 }}
              />
            )}
            {Icon && <Icon className="relative h-3.5 w-3.5" strokeWidth={2.25} />}
            {!iconOnly && <span className="relative">{option.label}</span>}
          </button>
        );
      })}
    </div>
  );
}
