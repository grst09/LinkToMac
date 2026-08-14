import { motion } from "framer-motion";
import type { SectionMeta } from "../theme/sections";

interface SectionHeaderProps {
  section: SectionMeta;
  subtitle?: string;
  trailing?: React.ReactNode;
}

/** Full-width card header for a section's content area — icon in an accent-tinted circle,
 *  title, subtitle, optional trailing toolbar content. Ported from SectionHeaderView.swift. */
export function SectionHeader({ section, subtitle, trailing }: SectionHeaderProps) {
  const Icon = section.icon;
  return (
    <motion.div
      initial={{ opacity: 0, y: -6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25, ease: "easeOut" }}
      className="flex items-center gap-3 px-5 py-4 border-b border-black/5 dark:border-white/10"
    >
      <span className={`flex h-9 w-9 items-center justify-center rounded-full ${section.accent.bg}`}>
        <Icon className={`h-4.5 w-4.5 ${section.accent.text}`} strokeWidth={2.25} />
      </span>
      <div className="min-w-0 flex-1">
        <h1 className="text-[15px] font-semibold text-neutral-900 dark:text-neutral-100 truncate">
          {section.label}
        </h1>
        {subtitle && (
          <p className="text-xs text-neutral-500 dark:text-neutral-400 truncate">{subtitle}</p>
        )}
      </div>
      {trailing}
    </motion.div>
  );
}
