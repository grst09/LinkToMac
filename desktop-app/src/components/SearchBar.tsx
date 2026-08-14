import { Search } from "lucide-react";

interface SearchBarProps {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
}

/** Reusable search field above a list — ported from SearchBarView.swift. */
export function SearchBar({ value, onChange, placeholder }: SearchBarProps) {
  return (
    <div className="relative px-3 py-2">
      <Search className="pointer-events-none absolute left-6 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-neutral-400" />
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full rounded-lg bg-black/[0.04] dark:bg-white/[0.06] py-1.5 pl-8 pr-3 text-[13px] text-neutral-900 dark:text-neutral-100 placeholder:text-neutral-400 focus:outline-none focus:ring-2 focus:ring-blue-500/40"
      />
    </div>
  );
}
