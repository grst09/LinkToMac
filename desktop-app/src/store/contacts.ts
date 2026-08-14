import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export interface Contact {
  id: string;
  name: string;
  phoneNumber: string;
  isStarred: boolean;
  email?: string | null;
  organization?: string | null;
}

interface ContactsState {
  contacts: Contact[];
  loaded: boolean;
  lastSyncedAt: number | null;
  lastError: string | null;
}

export const useContactsStore = create<ContactsState>(() => ({
  contacts: [],
  loaded: false,
  lastSyncedAt: null,
  lastError: null,
}));

/** Matches on the last 10 digits (or however many are shorter) — country-code/formatting
 *  differences between how Android reports a thread's address and a contact's stored number
 *  otherwise cause false negatives. Ported from MessagesView.swift's `phoneNumbersMatch`. */
export function phoneNumbersMatch(a: string, b: string): boolean {
  const digitsA = a.replace(/\D/g, "");
  const digitsB = b.replace(/\D/g, "");
  const length = Math.min(digitsA.length, digitsB.length, 10);
  if (length === 0) return false;
  return digitsA.slice(-length) === digitsB.slice(-length);
}

export function displayNameForAddress(address: string, contactName?: string | null): string {
  if (contactName) return contactName;
  const match = useContactsStore
    .getState()
    .contacts.find((c) => phoneNumbersMatch(c.phoneNumber, address));
  return match?.name ?? address;
}

export async function refreshContacts() {
  await invoke("refresh_contacts");
}

export async function dialContact(phoneNumber: string) {
  await invoke("dial_contact", { phoneNumber });
}

export async function updateContact(contact: {
  id: string;
  name: string;
  phoneNumber: string;
  isStarred: boolean;
  email?: string | null;
  organization?: string | null;
}) {
  await invoke("update_contact", contact);
}

export async function createContact(contact: {
  name: string;
  phoneNumber: string;
  email?: string | null;
  organization?: string | null;
}) {
  await invoke("create_contact", contact);
}

export async function deleteContact(id: string) {
  await invoke("delete_contact", { id });
}

let initialized = false;

export function initContactsListeners() {
  if (initialized) return;
  initialized = true;

  invoke<Contact[]>("list_contacts").then((contacts) => {
    useContactsStore.setState({ contacts, loaded: true, lastSyncedAt: Date.now() });
  });

  listen<Contact[]>("contacts-updated", (event) => {
    useContactsStore.setState({ contacts: event.payload, loaded: true, lastSyncedAt: Date.now() });
  });

  listen<string>("contacts-error", (event) => {
    useContactsStore.setState({ lastError: event.payload });
    setTimeout(() => useContactsStore.setState({ lastError: null }), 4000);
  });
}
