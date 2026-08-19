import { useEffect, useState } from "react";
import { motion } from "framer-motion";
import { Phone, MessageCircle, Copy, Check, Pencil, Trash2, Star, Users, X, CloudOff } from "lucide-react";
import { InitialsAvatar } from "./InitialsAvatar";
import { avatarColorHex } from "../theme/avatarColor";
import { requestMessageTo } from "../store/navigation";
import { dialContact, updateContact, createContact, deleteContact, type Contact } from "../store/contacts";

export function ContactDetailPanel({
  contact,
  pending,
  canEdit,
  onEdit,
  onDeleted,
}: {
  contact: Contact;
  pending: boolean;
  canEdit: boolean;
  onEdit: () => void;
  onDeleted: () => void;
}) {
  const [copied, setCopied] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const tint = avatarColorHex(contact.name);

  useEffect(() => setCopied(false), [contact.id]);

  return (
    <div
      className="h-full overflow-y-auto"
      style={{
        backgroundImage: `linear-gradient(to bottom, ${tint}73, ${tint}2e, transparent 45%)`,
      }}
    >
      <motion.div
        key={contact.id}
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.2 }}
        className="flex flex-col items-center gap-4 px-6 pb-8 pt-9"
      >
        <InitialsAvatar name={contact.name} diameter={108} />
        <div className="flex flex-col items-center gap-1">
          <h2 className="text-xl font-bold text-neutral-900 dark:text-neutral-100">{contact.name}</h2>
          {contact.isStarred && (
            <div className="flex items-center gap-1 text-xs text-neutral-500 dark:text-neutral-400">
              <Star className="h-3 w-3 fill-yellow-400 text-yellow-400" />
              Starred
            </div>
          )}
          {pending && (
            <div className="flex items-center gap-1 text-xs text-orange-600 dark:text-orange-400">
              <CloudOff className="h-3 w-3" />
              Not synced with your phone yet
            </div>
          )}
        </div>

        <div className="flex gap-5">
          <CircleAction
            icon={MessageCircle}
            tint="#10b981"
            onClick={() => requestMessageTo(contact.phoneNumber)}
          />
          <CircleAction icon={Phone} tint="#3b82f6" onClick={() => dialContact(contact.phoneNumber)} />
          <CircleAction
            icon={copied ? Check : Copy}
            tint="#6b7280"
            onClick={() => {
              navigator.clipboard.writeText(contact.phoneNumber);
              setCopied(true);
            }}
          />
        </div>

        <div className="w-full max-w-[360px] rounded-[10px] bg-black/[0.03] dark:bg-white/[0.06]">
          <InfoRow label="phone" value={contact.phoneNumber} />
          {contact.email && <InfoRow label="email" value={contact.email} border />}
          {contact.organization && <InfoRow label="organization" value={contact.organization} border />}
        </div>

        {canEdit && (
          <div className="w-full max-w-[360px] overflow-hidden rounded-[10px]">
            <button
              onClick={onEdit}
              className="flex w-full items-center justify-between bg-black/[0.03] dark:bg-white/[0.06] px-3 py-3 text-[13px] font-medium text-blue-600 dark:text-blue-400 hover:bg-black/[0.06] dark:hover:bg-white/[0.1] transition-colors"
            >
              Edit Contact
              <Pencil className="h-4 w-4" />
            </button>
            <div className="h-px bg-black/5 dark:bg-white/10" />
            <button
              onClick={() => setConfirmingDelete(true)}
              className="flex w-full items-center justify-between bg-black/[0.03] dark:bg-white/[0.06] px-3 py-3 text-[13px] font-medium text-red-600 dark:text-red-400 hover:bg-black/[0.06] dark:hover:bg-white/[0.1] transition-colors"
            >
              Delete Contact
              <Trash2 className="h-4 w-4" />
            </button>
          </div>
        )}
      </motion.div>

      {confirmingDelete && (
        <ConfirmDialog
          title={`Delete ${contact.name}?`}
          message="This can't be undone."
          onCancel={() => setConfirmingDelete(false)}
          onConfirm={async () => {
            await deleteContact(contact.id);
            setConfirmingDelete(false);
            onDeleted();
          }}
        />
      )}
    </div>
  );
}

export function ContactEditPanel({
  existing,
  onDone,
}: {
  existing: Contact | null;
  onDone: () => void;
}) {
  const [name, setName] = useState(existing?.name ?? "");
  const [phoneNumber, setPhoneNumber] = useState(existing?.phoneNumber ?? "");
  const [email, setEmail] = useState(existing?.email ?? "");
  const [organization, setOrganization] = useState(existing?.organization ?? "");
  const [isStarred, setIsStarred] = useState(existing?.isStarred ?? false);

  const canSave = name.trim() !== "" && phoneNumber.trim() !== "";

  async function save() {
    if (!canSave) return;
    const trimmedEmail = email.trim() || null;
    const trimmedOrg = organization.trim() || null;
    if (existing) {
      await updateContact({
        id: existing.id,
        name: name.trim(),
        phoneNumber: phoneNumber.trim(),
        isStarred,
        email: trimmedEmail,
        organization: trimmedOrg,
      });
    } else {
      await createContact({
        name: name.trim(),
        phoneNumber: phoneNumber.trim(),
        email: trimmedEmail,
        organization: trimmedOrg,
      });
    }
    onDone();
  }

  return (
    <div className="flex h-full flex-col items-center justify-center gap-5 px-6">
      <h2 className="text-lg font-bold text-neutral-900 dark:text-neutral-100">
        {existing ? "Edit Contact" : "New Contact"}
      </h2>
      <div className="flex w-full max-w-[320px] flex-col gap-3">
        <Field label="Name" value={name} onChange={setName} placeholder="Name" />
        <Field label="Phone Number" value={phoneNumber} onChange={setPhoneNumber} placeholder="Phone Number" />
        <Field label="Email" value={email} onChange={setEmail} placeholder="Optional" />
        <Field label="Organization" value={organization} onChange={setOrganization} placeholder="Optional" />
        <label className="flex items-center justify-between text-[13px] text-neutral-700 dark:text-neutral-300">
          Starred
          <input
            type="checkbox"
            checked={isStarred}
            onChange={(e) => setIsStarred(e.target.checked)}
            className="h-4 w-4 accent-blue-500"
          />
        </label>
      </div>
      <div className="flex gap-3">
        <button
          onClick={onDone}
          className="rounded-lg px-4 py-1.5 text-[13px] text-neutral-600 dark:text-neutral-300 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
        >
          Cancel
        </button>
        <button
          onClick={save}
          disabled={!canSave}
          className="rounded-lg bg-blue-500 px-4 py-1.5 text-[13px] font-medium text-white disabled:opacity-40 hover:bg-blue-600 transition-colors"
        >
          {existing ? "Save" : "Create"}
        </button>
      </div>
    </div>
  );
}

export function BulkSelectionPanel({ count, onDeleteAll }: { count: number; onDeleteAll: () => void }) {
  const [confirming, setConfirming] = useState(false);
  if (count === 0) {
    return <Placeholder icon={Check} text="Select contacts to delete" />;
  }
  return (
    <div className="flex h-full flex-col items-center justify-center gap-3">
      <Users className="h-10 w-10 text-neutral-400" />
      <p className="text-lg font-bold text-neutral-900 dark:text-neutral-100">
        {count} contact{count === 1 ? "" : "s"} selected
      </p>
      <button
        onClick={() => setConfirming(true)}
        className="rounded-lg border border-red-500/30 px-4 py-1.5 text-[13px] font-medium text-red-600 dark:text-red-400 hover:bg-red-500/10 transition-colors"
      >
        Delete Selected
      </button>
      {confirming && (
        <ConfirmDialog
          title={`Delete ${count} Contact${count === 1 ? "" : "s"}?`}
          message="This can't be undone."
          onCancel={() => setConfirming(false)}
          onConfirm={() => {
            setConfirming(false);
            onDeleteAll();
          }}
        />
      )}
    </div>
  );
}

export function Placeholder({ icon: Icon, text }: { icon: typeof Check; text: string }) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-2 text-neutral-400 dark:text-neutral-500">
      <Icon className="h-10 w-10" />
      <p className="text-sm">{text}</p>
    </div>
  );
}

export function ConfirmDialog({
  title,
  message,
  onConfirm,
  onCancel,
}: {
  title: string;
  message: string;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" onClick={onCancel}>
      <motion.div
        initial={{ opacity: 0, scale: 0.95 }}
        animate={{ opacity: 1, scale: 1 }}
        onClick={(e) => e.stopPropagation()}
        className="w-80 rounded-xl bg-white dark:bg-neutral-900 p-5 shadow-xl"
      >
        <div className="flex items-start justify-between">
          <h3 className="font-semibold text-neutral-900 dark:text-neutral-100">{title}</h3>
          <button onClick={onCancel} className="text-neutral-400 hover:text-neutral-600">
            <X className="h-4 w-4" />
          </button>
        </div>
        <p className="mt-1.5 text-sm text-neutral-500 dark:text-neutral-400">{message}</p>
        <div className="mt-4 flex justify-end gap-2">
          <button
            onClick={onCancel}
            className="rounded-lg px-3.5 py-1.5 text-[13px] text-neutral-600 dark:text-neutral-300 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={onConfirm}
            className="rounded-lg bg-red-500 px-3.5 py-1.5 text-[13px] font-medium text-white hover:bg-red-600 transition-colors"
          >
            Delete
          </button>
        </div>
      </motion.div>
    </div>
  );
}

function CircleAction({
  icon: Icon,
  tint,
  onClick,
}: {
  icon: typeof Phone;
  tint: string;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className="flex h-11 w-11 items-center justify-center rounded-full transition-transform hover:scale-105"
      style={{ backgroundColor: `${tint}26`, color: tint }}
    >
      <Icon className="h-4.5 w-4.5" />
    </button>
  );
}

function InfoRow({ label, value, border }: { label: string; value: string; border?: boolean }) {
  return (
    <div className={border ? "border-t border-black/5 dark:border-white/10 px-3 py-3" : "px-3 py-3"}>
      <p className="text-[11px] text-neutral-500 dark:text-neutral-400">{label}</p>
      <p className="text-[13px] text-neutral-900 dark:text-neutral-100">{value}</p>
    </div>
  );
}

function Field({
  label,
  value,
  onChange,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder: string;
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-[11px] text-neutral-500 dark:text-neutral-400">{label}</span>
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="rounded-lg border border-black/10 dark:border-white/15 bg-transparent px-2.5 py-1.5 text-[13px] focus:outline-none focus:ring-2 focus:ring-blue-500/40"
      />
    </label>
  );
}
