import { motion } from "framer-motion";
import { PanelLeftClose, PanelLeftOpen } from "lucide-react";
import { SECTIONS, SETTINGS_SECTION, type SectionId, type SectionMeta } from "../theme/sections";
import { DeviceCard } from "./DeviceCard";
import { useSidebarStore, toggleSidebarCollapsed } from "../store/sidebar";

interface SidebarProps {
  selection: SectionId;
  onSelect: (id: SectionId) => void;
}

const EXPANDED_WIDTH = 224;
const COLLAPSED_WIDTH = 60;

/** Sidebar: device card, nav list, Settings pinned at the bottom, collapse toggle beneath it.
 *  Ported from MainWindowView.swift's NavigationSplitView sidebar column. */
export function Sidebar({ selection, onSelect }: SidebarProps) {
  const collapsed = useSidebarStore((s) => s.collapsed);

  return (
    <motion.nav
      animate={{ width: collapsed ? COLLAPSED_WIDTH : EXPANDED_WIDTH }}
      transition={{ type: "spring", stiffness: 420, damping: 42 }}
      className="flex h-full shrink-0 flex-col overflow-hidden border-r border-black/5 dark:border-white/10 bg-black/[0.015] dark:bg-white/[0.02]"
    >
      <DeviceCard collapsed={collapsed} />

      <ul className="flex-1 overflow-y-auto overflow-x-hidden px-2 py-1">
        {SECTIONS.map((section) => (
          <NavItem
            key={section.id}
            section={section}
            active={selection === section.id}
            collapsed={collapsed}
            onClick={() => onSelect(section.id)}
          />
        ))}
      </ul>

      <div className="border-t border-black/5 dark:border-white/10 px-2 py-2">
        <NavItem
          section={SETTINGS_SECTION}
          active={selection === "settings"}
          collapsed={collapsed}
          onClick={() => onSelect("settings")}
        />
        <button
          onClick={toggleSidebarCollapsed}
          title={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          className={`mt-1 flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] text-neutral-500 dark:text-neutral-400 transition-colors hover:bg-black/[0.03] dark:hover:bg-white/[0.05] ${
            collapsed ? "justify-center" : ""
          }`}
        >
          {collapsed ? (
            <PanelLeftOpen className="h-4 w-4 shrink-0" strokeWidth={2} />
          ) : (
            <PanelLeftClose className="h-4 w-4 shrink-0" strokeWidth={2} />
          )}
          {!collapsed && <span className="truncate">Collapse</span>}
        </button>
      </div>
    </motion.nav>
  );
}

function NavItem({
  section,
  active,
  collapsed,
  onClick,
}: {
  section: SectionMeta;
  active: boolean;
  collapsed: boolean;
  onClick: () => void;
}) {
  const Icon = section.icon;
  return (
    <li>
      <button
        onClick={onClick}
        title={collapsed ? section.label : undefined}
        className={`relative flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] transition-colors ${
          collapsed ? "justify-center" : ""
        } ${
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
        <Icon className={`relative h-4 w-4 shrink-0 ${active ? section.accent.text : ""}`} strokeWidth={2} />
        {!collapsed && <span className="relative truncate">{section.label}</span>}
      </button>
    </li>
  );
}
