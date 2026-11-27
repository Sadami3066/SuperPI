import { useEffect, useState } from "react";
import { IconCopy, IconMinus, IconSquare, IconX } from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import {
  subscribeWindowMaximized,
  windowClose,
  windowIsMaximized,
  windowMinimize,
  windowToggleMaximize,
} from "../../platform/bridge";
import { useConnectionStore } from "../../stores/connectionStore";

export function WindowControls() {
  const { t } = useI18n();
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const [maximized, setMaximized] = useState(false);

  const visible = Boolean(desktopEnv?.isElectron && desktopEnv.platform !== "darwin");

  useEffect(() => {
    if (!visible) return;
    let active = true;
    void windowIsMaximized().then((val) => {
      if (active) setMaximized(val);
    });
    const unsubscribe = subscribeWindowMaximized((val) => {
      setMaximized(val);
    });
    return () => {
      active = false;
      unsubscribe();
    };
  }, [visible]);

  if (!visible) return null;

  return (
    <div className="pi-no-drag flex h-[44px] shrink-0 items-center">
      <button
        type="button"
        onClick={() => void windowMinimize()}
        aria-label={t("window.minimize")}
        title={t("window.minimize")}
        className="flex h-[44px] w-[40px] items-center justify-center transition-colors hover:bg-[var(--pi-hover)]"
        style={{ color: "var(--pi-ink-secondary)" }}
      >
        <IconMinus size={14} stroke={1.65} />
      </button>

      <button
        type="button"
        onClick={() => void windowToggleMaximize().then(setMaximized)}
        aria-label={maximized ? t("window.restore") : t("window.maximize")}
        title={maximized ? t("window.restore") : t("window.maximize")}
        className="flex h-[44px] w-[40px] items-center justify-center transition-colors hover:bg-[var(--pi-hover)]"
        style={{ color: "var(--pi-ink-secondary)" }}
      >
        {maximized ? <IconCopy size={12.5} stroke={1.65} /> : <IconSquare size={12} stroke={1.65} />}
      </button>

      <button
        type="button"
        onClick={() => void windowClose()}
        aria-label={t("window.close")}
        title={t("window.close")}
        className="flex h-[44px] w-[42px] items-center justify-center transition-colors hover:bg-[#e81123] hover:text-white"
        style={{ color: "var(--pi-ink-secondary)" }}
      >
        <IconX size={14} stroke={1.7} />
      </button>
    </div>
  );
}
