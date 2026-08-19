import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { Sidebar } from "./components/Sidebar";
import { ThisDeviceView } from "./components/ThisDeviceView";
import { NotificationsView } from "./components/NotificationsView";
import { MessagesView } from "./components/MessagesView";
import { ContactsView } from "./components/ContactsView";
import { PhotosView } from "./components/PhotosView";
import { FilesView } from "./components/FilesView";
import { ScreenMirrorView } from "./components/ScreenMirrorView";
import { NotesView } from "./components/NotesView";
import { ClipboardView } from "./components/ClipboardView";
import { SettingsView } from "./components/SettingsView";
import { type SectionId } from "./theme/sections";
import { initConnectionListeners } from "./store/connection";
import { initNotificationListeners } from "./store/notifications";
import { useNavigationStore, clearPendingSection } from "./store/navigation";

function App() {
  const [selection, setSelection] = useState<SectionId>("device");
  const pendingMessageAddress = useNavigationStore((s) => s.pendingMessageAddress);
  const pendingSection = useNavigationStore((s) => s.pendingSection);

  useEffect(() => {
    initConnectionListeners();
    initNotificationListeners();
  }, []);

  // Contacts' "Message" action sets pendingMessageAddress — switch to Messages so it can
  // consume it (open the matching thread or start composing), matching MainWindowView.swift.
  useEffect(() => {
    if (pendingMessageAddress) setSelection("messages");
  }, [pendingMessageAddress]);

  useEffect(() => {
    if (pendingSection) {
      setSelection(pendingSection);
      clearPendingSection();
    }
  }, [pendingSection]);

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-white dark:bg-neutral-950">
      <Sidebar selection={selection} onSelect={setSelection} />
      <main className="flex-1 overflow-hidden">
        <AnimatePresence mode="wait">
          <motion.div
            key={selection}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.15 }}
            className="h-full"
          >
            {selection === "device" ? (
              <ThisDeviceView />
            ) : selection === "notifications" ? (
              <NotificationsView />
            ) : selection === "messages" ? (
              <MessagesView />
            ) : selection === "contacts" ? (
              <ContactsView />
            ) : selection === "photos" ? (
              <PhotosView />
            ) : selection === "files" ? (
              <FilesView />
            ) : selection === "mirroring" ? (
              <ScreenMirrorView />
            ) : selection === "notes" ? (
              <NotesView />
            ) : selection === "clipboard" ? (
              <ClipboardView />
            ) : (
              <SettingsView />
            )}
          </motion.div>
        </AnimatePresence>
      </main>
    </div>
  );
}

export default App;
