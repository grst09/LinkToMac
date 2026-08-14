import { useEffect, useMemo, useRef, useState } from "react";
import { motion } from "framer-motion";
import { RefreshCw, SquarePen, MessageCircle, SmartphoneNfc, Send } from "lucide-react";
import { SectionHeader } from "./SectionHeader";
import { SearchBar } from "./SearchBar";
import { ResizableDivider } from "./ResizableDivider";
import { InitialsAvatar } from "./InitialsAvatar";
import { sectionMeta } from "../theme/sections";
import {
  initMessagesListeners,
  isLocalOnlyThread,
  sendSms,
  refreshMessages,
  useMessagesStore,
  type SmsThread,
} from "../store/messages";
import { displayNameForAddress, initContactsListeners, useContactsStore } from "../store/contacts";
import { useNavigationStore, clearPendingMessageAddress } from "../store/navigation";

export function MessagesView() {
  const { threads, loaded } = useMessagesStore();
  useContactsStore((s) => s.contacts); // re-render when contacts arrive, so names resolve
  const pendingAddress = useNavigationStore((s) => s.pendingMessageAddress);

  const [selectedThreadId, setSelectedThreadId] = useState<string | null>(null);
  const [searchText, setSearchText] = useState("");
  const [listWidth, setListWidth] = useState(280);
  const [composing, setComposing] = useState<{ address: string } | null>(null);

  useEffect(() => {
    initMessagesListeners();
    initContactsListeners();
  }, []);

  // Matches MessagesView.swift's dual-hook comment: handle a pending address both on mount
  // (Contacts may have set it and switched sections in the same beat) and on later change.
  useEffect(() => {
    if (!pendingAddress) return;
    const existing = threads.find((t) => t.address === pendingAddress);
    if (existing) {
      setComposing(null);
      setSelectedThreadId(existing.threadId);
    } else {
      setComposing({ address: pendingAddress });
      setSelectedThreadId(null);
    }
    clearPendingMessageAddress();
  }, [pendingAddress, threads]);

  useEffect(() => {
    if (!selectedThreadId && !composing && threads.length > 0) {
      setSelectedThreadId(threads[0].threadId);
    }
  }, [threads, selectedThreadId, composing]);

  const filteredThreads = useMemo(() => {
    if (!searchText.trim()) return threads;
    const q = searchText.toLowerCase();
    return threads.filter((t) => {
      const name = displayNameForAddress(t.address, t.contactName);
      const last = t.messages[t.messages.length - 1]?.body ?? "";
      return (
        name.toLowerCase().includes(q) ||
        t.address.toLowerCase().includes(q) ||
        last.toLowerCase().includes(q)
      );
    });
  }, [threads, searchText]);

  const selectedThread = threads.find((t) => t.threadId === selectedThreadId) ?? null;

  return (
    <div className="flex h-full flex-col">
      <SectionHeader
        section={sectionMeta("messages")}
        subtitle={`${threads.length} conversation${threads.length === 1 ? "" : "s"}`}
        trailing={
          <div className="flex gap-1.5">
            <button
              onClick={() => refreshMessages()}
              title="Refresh"
              className="rounded-md p-1.5 text-neutral-500 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
            >
              <RefreshCw className="h-3.5 w-3.5" />
            </button>
            <button
              onClick={() => {
                setComposing({ address: "" });
                setSelectedThreadId(null);
              }}
              title="New Message"
              className="rounded-md p-1.5 text-neutral-500 hover:bg-black/5 dark:hover:bg-white/10 transition-colors"
            >
              <SquarePen className="h-3.5 w-3.5" />
            </button>
          </div>
        }
      />

      <div className="flex flex-1 overflow-hidden">
        <div className="flex flex-col" style={{ width: listWidth }}>
          <SearchBar value={searchText} onChange={setSearchText} placeholder="Search conversations" />
          <div className="flex-1 overflow-y-auto">
            {!loaded ? null : threads.length === 0 ? (
              <EmptyState icon={MessageCircle} text="No messages yet" />
            ) : filteredThreads.length === 0 ? (
              <EmptyState icon={MessageCircle} text="No matching conversations" />
            ) : (
              <ul>
                {filteredThreads.map((thread) => (
                  <ThreadRow
                    key={thread.threadId}
                    thread={thread}
                    selected={thread.threadId === selectedThreadId}
                    onClick={() => {
                      setSelectedThreadId(thread.threadId);
                      setComposing(null);
                    }}
                  />
                ))}
              </ul>
            )}
          </div>
        </div>

        <ResizableDivider width={listWidth} onWidthChange={setListWidth} minWidth={200} maxWidth={460} />

        <div className="flex-1 overflow-hidden">
          {composing ? (
            <ComposeView
              initialAddress={composing.address}
              onSend={async (address, body) => {
                const threadId = await sendSms(address, body);
                setSelectedThreadId(threadId);
                setComposing(null);
              }}
              onCancel={() => setComposing(null)}
            />
          ) : selectedThread ? (
            <ConversationView thread={selectedThread} onSend={(body) => sendSms(selectedThread.address, body)} />
          ) : (
            <EmptyDetail />
          )}
        </div>
      </div>
    </div>
  );
}

function EmptyState({ icon: Icon, text }: { icon: typeof MessageCircle; text: string }) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-2 text-neutral-400 dark:text-neutral-500">
      <Icon className="h-8 w-8" />
      <p className="text-sm">{text}</p>
    </div>
  );
}

function EmptyDetail() {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-2 text-center text-neutral-400 dark:text-neutral-500">
      <MessageCircle className="h-11 w-11" />
      <p className="font-medium text-neutral-600 dark:text-neutral-300">Select a conversation</p>
      <p className="text-sm">Choose a conversation on the left, or start a new one.</p>
    </div>
  );
}

function ThreadRow({
  thread,
  selected,
  onClick,
}: {
  thread: SmsThread;
  selected: boolean;
  onClick: () => void;
}) {
  const name = displayNameForAddress(thread.address, thread.contactName);
  const last = thread.messages[thread.messages.length - 1];
  return (
    <li>
      <button
        onClick={onClick}
        className={`flex w-full items-center gap-2.5 px-3 py-2 text-left transition-colors ${
          selected ? "bg-blue-500/15" : "hover:bg-black/[0.03] dark:hover:bg-white/[0.05]"
        }`}
      >
        <InitialsAvatar name={name} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1.5">
            <span className="truncate text-[13px] font-semibold text-neutral-900 dark:text-neutral-100">
              {name}
            </span>
            {isLocalOnlyThread(thread.threadId) && (
              <span title="Not synced with your phone — see the conversation for details">
                <SmartphoneNfc className="h-3 w-3 shrink-0 text-neutral-400" />
              </span>
            )}
          </div>
          {last && (
            <p className="truncate text-xs text-neutral-500 dark:text-neutral-400">{last.body}</p>
          )}
        </div>
      </button>
    </li>
  );
}

function MessageInput({
  onSend,
  autoFocus,
}: {
  onSend: (body: string) => void;
  autoFocus?: boolean;
}) {
  const [draft, setDraft] = useState("");
  const trimmed = draft.trim();

  function send() {
    if (!trimmed) return;
    onSend(trimmed);
    setDraft("");
  }

  return (
    <div className="flex items-end gap-2 border-t border-black/5 dark:border-white/10 p-3">
      <textarea
        value={draft}
        autoFocus={autoFocus}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            send();
          }
        }}
        placeholder="Message"
        rows={1}
        className="flex-1 resize-none rounded-lg border border-black/10 dark:border-white/15 bg-transparent px-3 py-2 text-[13px] focus:outline-none focus:ring-2 focus:ring-blue-500/40"
      />
      <button
        onClick={send}
        disabled={!trimmed}
        className="rounded-lg bg-blue-500 p-2 text-white disabled:opacity-40 hover:bg-blue-600 transition-colors"
      >
        <Send className="h-4 w-4" />
      </button>
    </div>
  );
}

function ComposeView({
  initialAddress,
  onSend,
  onCancel,
}: {
  initialAddress: string;
  onSend: (address: string, body: string) => void;
  onCancel: () => void;
}) {
  const [address, setAddress] = useState(initialAddress);

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-2 border-b border-black/5 dark:border-white/10 p-3">
        <span className="text-sm text-neutral-500">To:</span>
        <input
          value={address}
          onChange={(e) => setAddress(e.target.value)}
          autoFocus={!initialAddress}
          placeholder="Phone number"
          className="flex-1 bg-transparent text-[13px] focus:outline-none"
        />
        <button onClick={onCancel} className="text-xs text-neutral-500 hover:text-neutral-700 dark:hover:text-neutral-300">
          Cancel
        </button>
      </div>
      <div className="flex flex-1 flex-col items-center justify-center gap-2 text-neutral-400 dark:text-neutral-500">
        <SquarePen className="h-9 w-9" />
        <p className="font-medium text-neutral-600 dark:text-neutral-300">New Message</p>
      </div>
      <MessageInput
        onSend={(body) => {
          if (!address.trim()) return;
          onSend(address.trim(), body);
        }}
      />
    </div>
  );
}

function ConversationView({ thread, onSend }: { thread: SmsThread; onSend: (body: string) => void }) {
  const name = displayNameForAddress(thread.address, thread.contactName);
  const scrollRef = useRef<HTMLDivElement>(null);
  const localOnly = isLocalOnlyThread(thread.threadId);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight });
  }, [thread.messages.length]);

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-2.5 px-3.5 py-2.5">
        <InitialsAvatar name={name} diameter={30} />
        <span className="font-semibold text-neutral-900 dark:text-neutral-100">{name}</span>
      </div>
      <div className="border-t border-black/5 dark:border-white/10" />
      {localOnly && (
        <div className="m-3 flex items-center gap-2 rounded-lg bg-orange-500/10 p-2.5 text-xs text-orange-700 dark:text-orange-400">
          <SmartphoneNfc className="h-4 w-4 shrink-0" />
          <span>
            Not synced with your phone — replies won't appear here. Check your phone's messaging
            app to continue this conversation.
          </span>
        </div>
      )}
      <div ref={scrollRef} className="flex-1 space-y-2 overflow-y-auto p-3">
        {thread.messages.map((m) => (
          <motion.div
            key={m.id}
            initial={{ opacity: 0, y: 4 }}
            animate={{ opacity: 1, y: 0 }}
            className={`flex ${m.isOutgoing ? "justify-end" : "justify-start"}`}
          >
            <div
              className={`max-w-[70%] rounded-2xl px-3 py-2 text-[13px] ${
                m.isOutgoing
                  ? "bg-blue-500 text-white"
                  : "bg-black/5 dark:bg-white/10 text-neutral-900 dark:text-neutral-100"
              }`}
            >
              {m.body}
            </div>
          </motion.div>
        ))}
      </div>
      <MessageInput onSend={onSend} />
    </div>
  );
}
