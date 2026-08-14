import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { Sidebar } from "./components/Sidebar";
import { ComingSoon } from "./components/ComingSoon";
import { ThisDeviceView } from "./components/ThisDeviceView";
import { NotificationsView } from "./components/NotificationsView";
import { sectionMeta, type SectionId } from "./theme/sections";
import { initConnectionListeners } from "./store/connection";
import { initNotificationListeners } from "./store/notifications";

type PlaceholderSectionId = Exclude<SectionId, "device" | "notifications">;

const COMING_SOON_DETAIL: Record<PlaceholderSectionId, string> = {
  messages: "SMS threads and reply-from-Mac will show up here.",
  photos: "A synced photo grid from your phone will show up here.",
  files: "Browse, upload, and download files from your phone's storage.",
  contacts: "Your phone's contacts, with call and message actions.",
  mirroring: "See and control your phone's screen from your Mac.",
  settings: "App settings aren't built yet — for now there's nothing to configure.",
};

function App() {
  const [selection, setSelection] = useState<SectionId>("device");

  useEffect(() => {
    initConnectionListeners();
    initNotificationListeners();
  }, []);

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
