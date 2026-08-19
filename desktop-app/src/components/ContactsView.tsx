import { useEffect, useMemo, useState } from "react";
import { RefreshCw, UserPlus, CheckSquare, Square, Trash2, Users, Search as SearchIcon, Phone, Star, CloudOff } from "lucide-react";
import { SectionHeader } from "./SectionHeader";
import { SearchBar } from "./SearchBar";
import { ResizableDivider } from "./ResizableDivider";
import { InitialsAvatar } from "./InitialsAvatar";
import {
  ContactDetailPanel,
  ContactEditPanel,
  BulkSelectionPanel,
  Placeholder,
  ConfirmDialog,
} from "./ContactDetail";
import { CallRow, CallDetailPanel } from "./CallDetail";
import { sectionMeta } from "../theme/sections";
import {
  initContactsListeners,
  refreshContacts,
  deleteContact,
  isPendingContact,
  useContactsStore,
  type Contact,
} from "../store/contacts";
import { initCallsListeners, useCallsStore } from "../store/calls";
import { useSyncSettingsStore } from "../store/syncSettings";

type SubTab = "contacts" | "calls";
type ContactRowItem = Contact & { pending: boolean };

export function ContactsView() {
  const { contacts, pending, loaded, lastSyncedAt, lastError } = useContactsStore();
  const { calls, loaded: callsLoaded } = useCallsStore();
  const contactsEnabled = useSyncSettingsStore((s) => s.settings.contactsEnabled);

  const combinedContacts: ContactRowItem[] = useMemo(
    () => [
      ...pending.map((c) => ({ ...c, isStarred: false, pending: true })),
      ...contacts.map((c) => ({ ...c, pending: false })),
    ],
    [contacts, pending]
  );

  const [subTab, setSubTab] = useState<SubTab>("contacts");
  const [searchText, setSearchText] = useState("");
  const [listWidth, setListWidth] = useState(320);
  const [selectedContactId, setSelectedContactId] = useState<string | null>(null);
  const [selectedCallId, setSelectedCallId] = useState<string | null>(null);
  const [isSelecting, setIsSelecting] = useState(false);
  const [checkedIds, setCheckedIds] = useState<Set<string>>(new Set());
  const [isCreating, setIsCreating] = useState(false);
  const [isEditing, setIsEditing] = useState(false);
  const [confirmingBulkDelete, setConfirmingBulkDelete] = useState(false);

  useEffect(() => {
    initContactsListeners();
    initCallsListeners();
  }, []);

  function switchTab(tab: SubTab) {
    setSubTab(tab);
    setSearchText("");
    setSelectedContactId(null);
    setSelectedCallId(null);
    setIsSelecting(false);
    setCheckedIds(new Set());
    setIsCreating(false);
    setIsEditing(false);
  }

  const filteredContacts = useMemo(() => {
    if (!searchText.trim()) return combinedContacts;
    const q = searchText.toLowerCase();
    return combinedContacts.filter((c) => c.name.toLowerCase().includes(q) || c.phoneNumber.includes(q));
  }, [combinedContacts, searchText]);

  const groupedContacts = useMemo(() => groupAlphabetically(filteredContacts), [filteredContacts]);

  const filteredCalls = useMemo(() => {
    if (!searchText.trim()) return calls;
    const q = searchText.toLowerCase();
    return calls.filter((c) => (c.contactName ?? "").toLowerCase().includes(q) || c.number.includes(q));
  }, [calls, searchText]);

  const selectedContact = combinedContacts.find((c) => c.id === selectedContactId) ?? null;
  const selectedCall = calls.find((c) => c.id === selectedCallId) ?? null;
  const allChecked = filteredContacts.length > 0 && filteredContacts.every((c) => checkedIds.has(c.id));

  return (
    <div className="flex h-full flex-col">
      <SectionHeader
        section={sectionMeta("contacts")}
        subtitle={
          subTab === "contacts"
            ? `${combinedContacts.length} contact${combinedContacts.length === 1 ? "" : "s"}${lastSyncedAt ? ` · Updated ${new Date(lastSyncedAt).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}` : ""}`
            : `${calls.length} call${calls.length === 1 ? "" : "s"}`
        }
        trailing={
          <div className="flex items-center gap-1.5">
            <IconButton icon={RefreshCw} title="Sync" onClick={() => refreshContacts()} />
            {subTab === "contacts" && !isSelecting && (
              <>
                <IconButton icon={CheckSquare} title="Select" onClick={() => setIsSelecting(true)} />
                <IconButton
                  icon={UserPlus}
                  title="New"
                  onClick={() => {
                    setIsCreating(true);
                    setSelectedContactId(null);
                  }}
                />
              </>
            )}
            {subTab === "contacts" && isSelecting && (
              <>
                {checkedIds.size > 0 && (
                  <button
                    onClick={() => setConfirmingBulkDelete(true)}
                    className="flex items-center gap-1 rounded-md border border-red-500/30 px-2 py-1 text-xs font-medium text-red-600 dark:text-red-400 hover:bg-red-500/10 transition-colors"
                  >
                    <Trash2 className="h-3 w-3" />
                    Delete ({checkedIds.size})
                  </button>
                )}
                <IconButton
                  icon={allChecked ? Square : CheckSquare}
                  title={allChecked ? "Deselect All" : "Select All"}
                  onClick={() =>
                    setCheckedIds(allChecked ? new Set() : new Set(filteredContacts.map((c) => c.id)))
                  }
                />
                <button
                  onClick={() => {
                    setIsSelecting(false);
                    setCheckedIds(new Set());
                  }}
                  className="rounded-md px-2 py-1 text-xs font-medium text-neutral-500 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
                >
                  Done
                </button>
              </>
            )}
          </div>
        }
      />

      {!contactsEnabled && subTab === "contacts" && (
        <div className="mx-4 mt-3 flex items-center gap-2 rounded-lg bg-orange-500/10 px-3 py-2 text-xs text-orange-700 dark:text-orange-400">
          <CloudOff className="h-3.5 w-3.5 shrink-0" />
          Contacts sync is off — new contacts stay on this Mac and sync to your phone once it's back on.
        </div>
      )}

      {lastError && (
        <div className="mx-4 mt-3 flex items-center gap-2 rounded-lg bg-orange-500/10 px-3 py-2 text-xs text-orange-700 dark:text-orange-400">
          {lastError}
        </div>
      )}

      <div className="flex flex-1 overflow-hidden">
        <div className="flex flex-col" style={{ width: listWidth }}>
          <div className="flex gap-1 rounded-lg bg-black/[0.04] dark:bg-white/[0.06] m-3 mb-0 p-0.5 text-[13px]">
            <button
              onClick={() => switchTab("contacts")}
              className={`flex-1 rounded-md py-1 font-medium transition-colors ${subTab === "contacts" ? "bg-white dark:bg-neutral-700 shadow-sm" : "text-neutral-500"}`}
            >
              Contacts
            </button>
            <button
              onClick={() => switchTab("calls")}
              className={`flex-1 rounded-md py-1 font-medium transition-colors ${subTab === "calls" ? "bg-white dark:bg-neutral-700 shadow-sm" : "text-neutral-500"}`}
            >
              Call History
            </button>
          </div>
          <SearchBar
            value={searchText}
            onChange={setSearchText}
            placeholder={subTab === "contacts" ? "Search contacts" : "Search calls"}
          />
          <div className="flex-1 overflow-y-auto">
            {subTab === "contacts" ? (
              !loaded ? null : combinedContacts.length === 0 ? (
                <Placeholder icon={Users} text="No contacts yet" />
              ) : filteredContacts.length === 0 ? (
                <Placeholder icon={SearchIcon} text="No matching contacts" />
              ) : (
                groupedContacts.map(([letter, group]) => (
                  <div key={letter}>
                    <div className="sticky top-0 z-10 bg-white/90 dark:bg-neutral-950/90 backdrop-blur px-3 py-1 text-xs font-semibold text-neutral-400">
                      {letter}
                    </div>
                    <ul>
                      {group.map((contact) => (
                        <ContactRow
                          key={contact.id}
                          contact={contact}
                          selected={contact.id === selectedContactId}
                          isSelecting={isSelecting}
                          checked={checkedIds.has(contact.id)}
                          onClick={() => {
                            if (isSelecting) {
                              setCheckedIds((prev) => {
                                const next = new Set(prev);
                                next.has(contact.id) ? next.delete(contact.id) : next.add(contact.id);
                                return next;
                              });
                            } else {
                              setSelectedContactId(contact.id);
                              setIsCreating(false);
                              setIsEditing(false);
                            }
                          }}
                        />
                      ))}
                    </ul>
                  </div>
                ))
              )
            ) : !callsLoaded ? null : calls.length === 0 ? (
              <Placeholder icon={Phone} text="No call history yet" />
            ) : filteredCalls.length === 0 ? (
              <Placeholder icon={SearchIcon} text="No matching calls" />
            ) : (
              <ul>
                {filteredCalls.map((call) => (
                  <CallRow
                    key={call.id}
                    call={call}
                    selected={call.id === selectedCallId}
                    onClick={() => setSelectedCallId(call.id)}
                  />
                ))}
              </ul>
            )}
          </div>
        </div>

        <ResizableDivider width={listWidth} onWidthChange={setListWidth} minWidth={260} maxWidth={560} />

        <div className="m-3 flex-1 overflow-hidden rounded-2xl bg-black/[0.015] dark:bg-white/[0.02]">
          {subTab === "contacts" ? (
            isCreating ? (
              <ContactEditPanel existing={null} onDone={() => setIsCreating(false)} />
            ) : isEditing && selectedContact ? (
              <ContactEditPanel existing={selectedContact} onDone={() => setIsEditing(false)} />
            ) : isSelecting ? (
              <BulkSelectionPanel
                count={checkedIds.size}
                onDeleteAll={async () => {
                  for (const id of checkedIds) await deleteContact(id);
                  setCheckedIds(new Set());
                  setIsSelecting(false);
                }}
              />
            ) : selectedContact ? (
              <ContactDetailPanel
                contact={selectedContact}
                pending={selectedContact.pending}
                canEdit={contactsEnabled || isPendingContact(selectedContact.id)}
                onEdit={() => setIsEditing(true)}
                onDeleted={() => setSelectedContactId(null)}
              />
            ) : (
              <Placeholder icon={Users} text="Select a contact" />
            )
          ) : selectedCall ? (
            <CallDetailPanel call={selectedCall} />
          ) : (
            <Placeholder icon={Phone} text="Select a call" />
          )}
        </div>
      </div>

      {confirmingBulkDelete && (
        <ConfirmDialog
          title={`Delete ${checkedIds.size} Contact${checkedIds.size === 1 ? "" : "s"}?`}
          message="This can't be undone."
          onCancel={() => setConfirmingBulkDelete(false)}
          onConfirm={async () => {
            for (const id of checkedIds) await deleteContact(id);
            setCheckedIds(new Set());
            setIsSelecting(false);
            setConfirmingBulkDelete(false);
          }}
        />
      )}
    </div>
  );
}

function ContactRow({
  contact,
  selected,
  isSelecting,
  checked,
  onClick,
}: {
  contact: ContactRowItem;
  selected: boolean;
  isSelecting: boolean;
  checked: boolean;
  onClick: () => void;
}) {
  return (
    <li>
      <button
        onClick={onClick}
        className={`flex w-full items-center gap-2.5 px-3 py-1.5 text-left transition-colors ${
          selected && !isSelecting ? "bg-blue-500/15" : "hover:bg-black/[0.03] dark:hover:bg-white/[0.05]"
        }`}
      >
        {isSelecting &&
          (checked ? (
            <CheckSquare className="h-4 w-4 shrink-0 text-blue-500" />
          ) : (
            <Square className="h-4 w-4 shrink-0 text-neutral-300 dark:text-neutral-600" />
          ))}
        <InitialsAvatar name={contact.name} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1">
            <span className="truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
              {contact.name}
            </span>
            {contact.isStarred && <Star className="h-3 w-3 shrink-0 fill-yellow-400 text-yellow-400" />}
            {contact.pending && (
              <span
                title="Not synced with your phone yet"
                className="shrink-0 rounded-full bg-orange-500/10 px-1.5 py-0.5 text-[10px] font-medium text-orange-600 dark:text-orange-400"
              >
                Not synced
              </span>
            )}
          </div>
          <p className="truncate text-xs text-neutral-500 dark:text-neutral-400">{contact.phoneNumber}</p>
        </div>
      </button>
    </li>
  );
}

function IconButton({ icon: Icon, title, onClick }: { icon: typeof RefreshCw; title: string; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      title={title}
      className="rounded-md p-1.5 text-neutral-500 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
    >
      <Icon className="h-3.5 w-3.5" />
    </button>
  );
}

/** Group by first letter (uppercased) if it's a letter, else bucket into "#" — sections sorted
 *  so "#" sorts before "A" (matches Swift's default string ordering), contacts within a section
 *  sorted case-insensitively. Ported from ContactsView.swift's `groupedContacts`. */
function groupAlphabetically(contacts: ContactRowItem[]): [string, ContactRowItem[]][] {
  const groups = new Map<string, ContactRowItem[]>();
  for (const contact of contacts) {
    const trimmed = contact.name.trim();
    const first = trimmed[0]?.toUpperCase() ?? "";
    const key = /[A-Z]/.test(first) ? first : "#";
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(contact);
  }
  for (const group of groups.values()) {
    group.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" }));
  }
  return [...groups.entries()].sort(([a], [b]) => (a === "#" ? -1 : b === "#" ? 1 : a.localeCompare(b)));
}
