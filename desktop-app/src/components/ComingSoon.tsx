import { motion } from "framer-motion";
import type { SectionMeta } from "../theme/sections";

interface ComingSoonProps {
  section: SectionMeta;
  detail: string;
}

/** Placeholder for sidebar sections that exist in the shell but aren't built yet — keeps the
 *  navigation honest about what actually works today. Ported from ComingSoonView.swift. */
export function ComingSoon({ section, detail }: ComingSoonProps) {
  const Icon = section.icon;
  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.97 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={{ duration: 0.3, ease: "easeOut" }}
      className="flex h-full flex-col items-center justify-center gap-3 px-8 text-center"
    >
      <span className={`flex h-16 w-16 items-center justify-center rounded-full ${section.accent.bg}`}>
        <Icon className={`h-7 w-7 ${section.accent.text}`} strokeWidth={1.75} />
      </span>
      <h2 className="text-lg font-semibold text-neutral-900 dark:text-neutral-100">
        {section.label}
      </h2>
      <p className="max-w-xs text-sm text-neutral-500 dark:text-neutral-400">{detail}</p>
    </motion.div>
  );
}
