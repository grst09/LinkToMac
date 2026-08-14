import {
  Phone,
  PhoneIncoming,
  PhoneOutgoing,
  PhoneMissed,
  Voicemail,
  MessageCircle,
} from "lucide-react";
import { dialContact } from "../store/contacts";
import { requestMessageTo } from "../store/navigation";
import { relativeTime } from "../utils/relativeTime";
import type { CallLogEntry, CallType } from "../store/calls";

function iconAndColor(type: CallType): { icon: typeof Phone; className: string } {
  switch (type) {
    case "incoming":
      return { icon: PhoneIncoming, className: "text-neutral-500 dark:text-neutral-400" };
    case "outgoing":
      return { icon: PhoneOutgoing, className: "text-neutral-500 dark:text-neutral-400" };
    case "missed":
      return { icon: PhoneMissed, className: "text-red-500" };
    case "rejected":
    case "blocked":
      return { icon: PhoneMissed, className: "text-red-500" };
    case "voicemail":
      return { icon: Voicemail, className: "text-neutral-500 dark:text-neutral-400" };
    default:
      return { icon: Phone, className: "text-neutral-500 dark:text-neutral-400" };
  }
}

function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${s.toString().padStart(2, "0")}`;
}

export function CallRow({
  call,
  selected,
  onClick,
}: {
  call: CallLogEntry;
  selected: boolean;
  onClick: () => void;
}) {
  const { icon: Icon, className } = iconAndColor(call.type);
  return (
    <li>
      <button
        onClick={onClick}
        className={`flex w-full items-center gap-2.5 px-3 py-2 text-left transition-colors ${
          selected ? "bg-blue-500/15" : "hover:bg-black/[0.03] dark:hover:bg-white/[0.05]"
        }`}
      >
        <Icon className={`h-4 w-4 shrink-0 ${className}`} />
        <div className="min-w-0 flex-1">
          <p className="truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
            {call.contactName ?? call.number}
          </p>
          {call.contactName && (
            <p className="truncate text-xs text-neutral-500 dark:text-neutral-400">{call.number}</p>
          )}
        </div>
        <div className="shrink-0 text-right">
          <p className="text-xs text-neutral-400">{relativeTime(call.date)}</p>
          {call.durationSeconds > 0 && (
            <p className="text-xs text-neutral-400">{formatDuration(call.durationSeconds)}</p>
          )}
        </div>
      </button>
    </li>
  );
}

export function CallDetailPanel({ call }: { call: CallLogEntry }) {
  const { icon: Icon, className } = iconAndColor(call.type);
  const name = call.contactName ?? call.number;

  return (
    <div className="flex h-full flex-col items-center justify-center gap-4 px-6">
      <Icon className={`h-10 w-10 ${className}`} />
      <div className="text-center">
        <h2 className="text-lg font-bold text-neutral-900 dark:text-neutral-100">{name}</h2>
        {call.contactName && <p className="text-neutral-500 dark:text-neutral-400">{call.number}</p>}
      </div>

      <div className="w-full max-w-[280px] space-y-1.5 text-[13px]">
        <div className="flex justify-between text-neutral-600 dark:text-neutral-300">
          <span className="text-neutral-400">Type</span>
          <span className="capitalize">{call.type}</span>
        </div>
        <div className="flex justify-between text-neutral-600 dark:text-neutral-300">
          <span className="text-neutral-400">Date</span>
          <span>{new Date(call.date).toLocaleString()}</span>
        </div>
        {call.durationSeconds > 0 && (
          <div className="flex justify-between text-neutral-600 dark:text-neutral-300">
            <span className="text-neutral-400">Duration</span>
            <span>{formatDuration(call.durationSeconds)}</span>
          </div>
        )}
      </div>

      <div className="flex gap-6">
        <DetailAction icon={Phone} label="Call" tint="#10b981" onClick={() => dialContact(call.number)} />
        <DetailAction
          icon={MessageCircle}
          label="Message"
          tint="#3b82f6"
          onClick={() => requestMessageTo(call.number)}
        />
      </div>
    </div>
  );
}

function DetailAction({
  icon: Icon,
  label,
  tint,
  onClick,
}: {
  icon: typeof Phone;
  label: string;
  tint: string;
  onClick: () => void;
}) {
  return (
    <button onClick={onClick} className="flex flex-col items-center gap-1.5">
      <span
        className="flex h-11 w-11 items-center justify-center rounded-full transition-transform hover:scale-105"
        style={{ backgroundColor: `${tint}26`, color: tint }}
      >
        <Icon className="h-4.5 w-4.5" />
      </span>
      <span className="text-xs text-neutral-500 dark:text-neutral-400">{label}</span>
    </button>
  );
}
