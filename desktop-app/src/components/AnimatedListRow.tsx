import { motion, type HTMLMotionProps } from "framer-motion";

interface AnimatedListRowProps extends Omit<HTMLMotionProps<"li">, "ref"> {
  /** Position within the list — adds a small stagger delay so rows cascade in instead of
   *  popping in all at once. Omit for rows that appear one at a time (e.g. a new clipboard
   *  entry arriving live), where a delay would just make it feel laggy. */
  index?: number;
}

/** Shared list-row enter/exit: collapses height in, slides out on removal. Extracted from what
 *  used to be near-identical `motion.li` blocks copy-pasted across NotificationsView and
 *  ClipboardView (and now reused by ThisDeviceView's paired-device rows). */
export function AnimatedListRow({ index, className, children, transition, ...props }: AnimatedListRowProps) {
  const delay = index != null ? Math.min(index, 8) * 0.03 : 0;
  return (
    <motion.li
      layout
      initial={{ opacity: 0, y: -8, height: 0 }}
      animate={{ opacity: 1, y: 0, height: "auto" }}
      exit={{ opacity: 0, x: 24 }}
      transition={{ duration: 0.2, ease: "easeOut", delay, ...transition }}
      className={className}
      {...props}
    >
      {children}
    </motion.li>
  );
}
