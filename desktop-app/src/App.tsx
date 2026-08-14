import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { Sidebar } from "./components/Sidebar";
import { ComingSoon } from "./components/ComingSoon";
import { ThisDeviceView } from "./components/ThisDeviceView";
import { NotificationsView } from "./components/NotificationsView";
import { MessagesView } from "./components/MessagesView";
import { ContactsView } from "./components/ContactsView";
import { PhotosView } from "./components/PhotosView";
import { FilesView } from "./components/FilesView";
import { sectionMeta, type SectionId } from "./theme/sections";
import { initConnectionListeners } from "./store/connection";
import { initNotificationListeners } from "./store/notifications";
import { useNavigationStore } from "./store/navigation";

type PlaceholderSectionId = Exclude<
  SectionId,
  "device" | "notifications" | "messages" | "contacts" | "photos" | "files"
>;

const COMING_SOON_DETAIL: Record<PlaceholderSectionId, string> = {
  mirroring: "See and control your phone's screen from your Mac.",
  settings: "App settings aren't built yet — for now there's nothing to configure.",
};

function App() {
  const [selection, setSelection] = useState<SectionId>("device");
  const pendingMessageAddress = useNavigationStore((s) => s.pendingMessageAddress);

  useEffect(() => {
    initConnectionListeners();
    initNotificationListeners();
  }, []);

  // Contacts' "Message" action sets pendingMessageAddress — switch to Messages so it can
  // consume it (open the matching thread or start composing), matching MainWindowView.swift.
  useEffect(() => {
    if (pendingMessageAddress) setSelection("messages");
  }, [pendingMessageAddress]);

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
            ) : (
              <ComingSoon
                section={sectionMeta(selection)}
                detail={COMING_SOON_DETAIL[selection as PlaceholderSectionId]}
              />
            )}
          </motion.div>
        </AnimatePresence>
      </main>
    </div>
  );
}

export default App;
