import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { Sidebar } from "./components/Sidebar";
import { ComingSoon } from "./components/ComingSoon";
import { ThisDeviceView } from "./components/ThisDeviceView";
import { sectionMeta, type SectionId } from "./theme/sections";
import { initConnectionListeners } from "./store/connection";

const COMING_SOON_DETAIL: Record<Exclude<SectionId, "device">, string> = {
  notifications: "Mirrored notifications from your phone will show up here.",
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
            ) : (
              <ComingSoon section={sectionMeta(selection)} detail={COMING_SOON_DETAIL[selection]} />
            )}
          </motion.div>
        </AnimatePresence>
      </main>
    </div>
  );
}

export default App;
