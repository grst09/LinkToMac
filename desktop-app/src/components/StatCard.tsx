import { motion } from "framer-motion";
import type { LucideIcon } from "lucide-react";

interface StatCardProps {
  icon: LucideIcon;
  label: string;
  value: string;
  accent: { text: string; bg: string };
  /** Fills the whole tile with the accent tint instead of just the icon badge — for the one
   *  stat that deserves to stand out (e.g. a metric needing attention). */
  highlighted?: boolean;
}

/** Elevated metric tile: icon badge, bold value, label — the "16h / 79% / 3.2k" treatment. */
export function StatCard({ icon: Icon, label, value, accent, highlighted }: StatCardProps) {
  return (
    <motion.div
      whileHover={{ y: -2 }}
      transition={{ type: "spring", stiffness: 400, damping: 30 }}
      className={`rounded-2xl border p-4 shadow-soft transition-shadow hover:shadow-soft-hover ${
        highlighted
          ? `border-transparent ${accent.bg}`
          : "border-black/5 dark:border-white/10 bg-white dark:bg-neutral-900"
      }`}
    >
      <span className={`flex h-8 w-8 items-center justify-center rounded-full ${accent.bg}`}>
        <Icon className={`h-4 w-4 ${accent.text}`} strokeWidth={2.25} />
      </span>
      <p className="mt-3 text-2xl font-semibold tracking-tight text-neutral-900 dark:text-neutral-100">
        {value}
      </p>
      <p className="text-xs text-neutral-500 dark:text-neutral-400">{label}</p>
    </motion.div>
  );
}
