import { useCallback, useRef } from "react";

interface ResizableDividerProps {
  width: number;
  onWidthChange: (width: number) => void;
  minWidth: number;
  maxWidth: number;
}

/** Draggable divider between a fixed-width list column and the detail panel next to it —
 *  ported from ResizableDivider.swift. A 6px hit target (wider than the visible 1px line) with
 *  a resize cursor, clamped to [minWidth, maxWidth]. */
export function ResizableDivider({ width, onWidthChange, minWidth, maxWidth }: ResizableDividerProps) {
  const dragState = useRef<{ startX: number; startWidth: number } | null>(null);

  const onPointerMove = useCallback(
    (e: PointerEvent) => {
      if (!dragState.current) return;
      const delta = e.clientX - dragState.current.startX;
      const next = Math.min(maxWidth, Math.max(minWidth, dragState.current.startWidth + delta));
      onWidthChange(next);
    },
    [minWidth, maxWidth, onWidthChange],
  );

  const onPointerUp = useCallback(() => {
    dragState.current = null;
    window.removeEventListener("pointermove", onPointerMove);
    window.removeEventListener("pointerup", onPointerUp);
  }, [onPointerMove]);

  const onPointerDown = (e: React.PointerEvent) => {
    dragState.current = { startX: e.clientX, startWidth: width };
    window.addEventListener("pointermove", onPointerMove);
    window.addEventListener("pointerup", onPointerUp);
  };

  return (
    <div
      onPointerDown={onPointerDown}
      className="w-1.5 shrink-0 cursor-col-resize group relative"
    >
      <div className="absolute inset-y-0 left-1/2 w-px -translate-x-1/2 bg-black/5 dark:bg-white/10 group-hover:bg-black/15 dark:group-hover:bg-white/25 transition-colors" />
    </div>
  );
}
