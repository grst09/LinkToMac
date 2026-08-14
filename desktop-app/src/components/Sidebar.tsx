import { motion } from "framer-motion";
import { SECTIONS, SETTINGS_SECTION, type SectionId } from "../theme/sections";
import { DeviceCard } from "./DeviceCard";

interface SidebarProps {
  selection: SectionId;
  onSelect: (id: SectionId) => void;
}

/** Sidebar: device card, nav list, Settings pinned at the bottom. Ported from
 *  MainWindowView.swift's NavigationSplitView sidebar column. */
export function Sidebar({ selection, onSelect }: SidebarProps) {
  return (
    <nav className="flex h-full w-56 shrink-0 flex-col border-r border-black/5 dark:border-white/10 bg-black/[0.015] dark:bg-white/[0.02]">
      <DeviceCard />

      <ul className="flex-1 overflow-y-auto px-2 py-1">
        {SECTIONS.map((section) => (
          <NavItem
            key={section.id}
            section={section}
            active={selection === section.id}
            onClick={() => onSelect(section.id)}
          />
        ))}
      </ul>

      <div className="border-t border-black/5 dark:border-white/10 px-2 py-2">
        <NavItem
          section={SETTINGS_SECTION}
          active={selection === "settings"}
          onClick={() => onSelect("settings")}
        />
      </div>
    </nav>
  );
}

function NavItem({
  section,
  active,
  onClick,
}: {
  section: (typeof SECTIONS)[number];
  active: boolean;
  onClick: () => void;
}) {
  const Icon = section.icon;
  return (
    <li>
      <button
        onClick={onClick}
        className={`relative flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] transition-colors ${
          active
            ? "text-neutral-900 dark:text-neutral-100"
            : "text-neutral-600 dark:text-neutral-400 hover:bg-black/[0.03] dark:hover:bg-white/[0.05]"
        }`}
      >
        {active && (
          <motion.span
            layoutId="nav-active"
            className={`absolute inset-0 rounded-lg ${section.accent.bg}`}
            transition={{ type: "spring", stiffness: 500, damping: 40 }}
          />
        )}
        <Icon
          className={`relative h-4 w-4 shrink-0 ${active ? section.accent.text : ""}`}
          strokeWidth={2}
        />
        <span className="relative truncate">{section.label}</span>
      </button>
    </li>
  );
}
