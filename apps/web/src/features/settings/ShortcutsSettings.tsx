import { useI18n, type I18nKey } from "../../i18n";
import { formatShortcut } from "../../platform/shortcuts";
import { useConnectionStore } from "../../stores/connectionStore";

export function ShortcutsSettings() {
  const { t } = useI18n();
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const platform = desktopEnv?.platform;
  const isElectron = Boolean(desktopEnv?.isElectron);

  const items: Array<{ labelKey: I18nKey; keys: string[]; electronOnly?: boolean }> = [
    { labelKey: "settings.shortcutNewChat", keys: ["mod", "n"] },
    { labelKey: "settings.shortcutSearch", keys: ["mod", "k"] },
    { labelKey: "settings.shortcutSidebar", keys: ["mod", "b"] },
    { labelKey: "settings.shortcutSuper", keys: ["mod", "shift", "s"] },
    { labelKey: "settings.shortcutSwitchChat", keys: ["ctrl", "tab"] },
    { labelKey: "settings.shortcutSettings", keys: ["mod", ","] },
    { labelKey: "settings.shortcutFiles", keys: ["mod", "shift", "e"] },
    { labelKey: "settings.shortcutDiff", keys: ["mod", "shift", "g"] },
    { labelKey: "settings.shortcutTerminal", keys: ["mod", "j"] },
    { labelKey: "settings.shortcutBrowser", keys: ["mod", "shift", "b"], electronOnly: true },
  ];

  const visible = items.filter((it) => !it.electronOnly || isElectron);

  return (
    <div className="divide-y divide-[var(--pi-hairline)]">
      {visible.map((item) => (
        <div key={item.labelKey} className="flex items-center justify-between gap-4 py-3.5">
          <span className="text-[13.5px]" style={{ color: "var(--pi-ink)" }}>
            {t(item.labelKey)}
          </span>
          <kbd
            className="shrink-0 rounded-[7px] px-2.5 py-1 text-[11.5px] font-medium"
            style={{
              background: "var(--pi-paper)",
              border: "0.5px solid var(--pi-hairline)",
              color: "var(--pi-ink-secondary)",
              fontFamily: "var(--pi-font-mono)",
            }}
          >
            {formatShortcut(item.keys, platform)}
          </kbd>
        </div>
      ))}
    </div>
  );
}
