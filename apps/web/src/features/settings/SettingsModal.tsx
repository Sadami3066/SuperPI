import * as DialogPrimitive from "@radix-ui/react-dialog";
import { type ReactNode, useEffect, useMemo, useState } from "react";
import { IconKeyboard, IconUserCircle, IconWorld } from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import { useSettingsStore } from "../../stores/settingsStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { Button, Input, NoticeBar } from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";
import { AppearanceSettings } from "./AppearanceSettings";
import { ArchivedSettings } from "./ArchivedSettings";
import { BrowserSettings } from "./BrowserSettings";
import { DiagnosticsSettings } from "./DiagnosticsSettings";
import { GeneralSettings } from "./GeneralSettings";
import { PersonalizationSettings } from "./PersonalizationSettings";
import { ProvidersSettings } from "./ProvidersSettings";
import { RelaySettings } from "./RelaySettings";
import { ShortcutsSettings } from "./ShortcutsSettings";

export type SettingsTabId =
  | "general"
  | "appearance"
  | "personalization"
  | "browser"
  | "shortcuts"
  | "providers"
  | "relay"
  | "archived"
  | "diagnostics";

interface SettingsNavItem {
  id: SettingsTabId;
  group: "app" | "aiSync" | "dataSystem";
  label: string;
  desc: string;
  keywords: string;
  icon: ReactNode;
}

export function SettingsModal({
  open,
  tab,
  autoCheckUpdateTick = 0,
  onSelectTab,
  onOpenChange,
  onOpenSession,
}: {
  open: boolean;
  tab: SettingsTabId;
  autoCheckUpdateTick?: number;
  onSelectTab: (tab: SettingsTabId) => void;
  onOpenChange: (open: boolean) => void;
  onOpenSession?: () => void;
}) {
  const { t } = useI18n();
  const [search, setSearch] = useState("");
  const settingsError = useSettingsStore((s) => s.settingsError);
  const clearSettingsError = useSettingsStore((s) => s.clearSettingsError);

  useEffect(() => {
    if (!open) {
      setSearch("");
      clearSettingsError();
      return undefined;
    }
    useWorkbenchStore.getState().pushOverlay();
    return () => {
      useWorkbenchStore.getState().popOverlay();
    };
  }, [clearSettingsError, open]);

  useEffect(() => {
    clearSettingsError();
  }, [clearSettingsError, tab]);

  const navItems = useMemo<SettingsNavItem[]>(
    () => [
      {
        id: "general",
        group: "app",
        label: t("settings.navGeneralOnly"),
        desc: t("settings.generalDesc"),
        keywords: `${t("settings.language")} ${t("settings.sendKey")} ${t("settings.compactionThreshold")} ${t("settings.autoRetry")} language send retry`,
        icon: <BrandIcon name="system/settings" size={15} />,
      },
      {
        id: "appearance",
        group: "app",
        label: t("settings.navAppearance"),
        desc: t("settings.appearanceDesc"),
        keywords: `${t("settings.appearance")} ${t("settings.themePreset")} ${t("settings.accentColor")} ${t("settings.fontStyle")} ${t("settings.chatFontScale")} dark light theme font color`,
        icon: <BrandIcon name="system/appearance" size={15} />,
      },
      {
        id: "personalization",
        group: "app",
        label: t("settings.navPersonalization"),
        desc: t("settings.personalizationDesc"),
        keywords: `${t("settings.nicknameLabel")} ${t("settings.customInstructionLabel")} profile nickname instruction prompt`,
        icon: <IconUserCircle size={15.5} stroke={1.65} />,
      },
      {
        id: "browser",
        group: "app",
        label: t("settings.navBrowser"),
        desc: t("settings.browserDesc"),
        keywords: `browser cookie account storage cache search engine 浏览器 账号 登录态 缓存 存储 搜索引擎`,
        icon: <IconWorld size={15.5} stroke={1.65} />,
      },
      {
        id: "shortcuts",
        group: "app",
        label: t("settings.navShortcuts"),
        desc: t("settings.shortcutsDesc"),
        keywords: `shortcut hotkey keybinding 快捷键 键盘`,
        icon: <IconKeyboard size={15.5} stroke={1.65} />,
      },
      {
        id: "providers",
        group: "aiSync",
        label: t("settings.navProviders"),
        desc: t("settings.providersDesc"),
        keywords: `api key oauth model provider openai anthropic deepseek 模型 厂商`,
        icon: <BrandIcon name="ai/model_provider" size={15} />,
      },
      {
        id: "relay",
        group: "aiSync",
        label: t("settings.navRelay"),
        desc: t("settings.relayDesc"),
        keywords: `relay sync pair device remote android 远程 配对 互联 同步`,
        icon: <BrandIcon name="system/device_connected" size={15} />,
      },
      {
        id: "archived",
        group: "dataSystem",
        label: t("settings.navArchived"),
        desc: t("settings.archivedDesc"),
        keywords: `archive history 归档 历史`,
        icon: <BrandIcon name="action/archive" size={15} />,
      },
      {
        id: "diagnostics",
        group: "dataSystem",
        label: t("settings.navAboutSystem"),
        desc: t("settings.aboutSystemDesc"),
        keywords: `about update version crash license privacy diagnostics status health 关于 更新 版本 崩溃 许可 隐私 诊断 状态`,
        icon: <BrandIcon name="status/info" size={15} />,
      },
    ],
    [t],
  );

  const filteredItems = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return navItems;
    return navItems.filter(
      (item) =>
        item.label.toLowerCase().includes(q) ||
        item.desc.toLowerCase().includes(q) ||
        item.keywords.toLowerCase().includes(q),
    );
  }, [navItems, search]);

  const groups: Array<{ id: SettingsNavItem["group"]; label: string }> = [
    { id: "app", label: t("settings.groupApp") },
    { id: "aiSync", label: t("settings.groupAiSync") },
    { id: "dataSystem", label: t("settings.groupDataSystem") },
  ];

  const activeNav = navItems.find((n) => n.id === tab) ?? navItems[0];

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay
          className="pi-dialog-overlay fixed inset-0 z-50"
          style={{ background: "var(--pi-scrim)" }}
        />
        <DialogPrimitive.Content
          aria-describedby={undefined}
          className="pi-popover pi-dialog-content fixed top-1/2 left-1/2 z-50 flex h-[min(720px,90vh)] w-[min(960px,94vw)] -translate-x-1/2 -translate-y-1/2 overflow-hidden rounded-[16px] p-0 outline-none"
        >
          <aside
            className="hidden w-[232px] shrink-0 flex-col overflow-y-auto p-3 sm:flex"
            style={{
              background: "var(--pi-paper)",
              borderRight: "0.5px solid var(--pi-hairline)",
            }}
          >
            <div className="px-2 pt-1 pb-2.5">
              <DialogPrimitive.Title
                className="text-[15px] font-semibold tracking-tight"
                style={{ color: "var(--pi-ink)" }}
              >
                {t("settings.title")}
              </DialogPrimitive.Title>
            </div>

            <div className="relative mb-3 px-0.5">
              <BrandIcon
                name="action/search"
                size={13.5}
                className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2"
                style={{ color: "var(--pi-ink-tertiary)" }}
              />
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder={t("settings.searchPlaceholder")}
                className="h-8 rounded-full pl-8 text-[12.5px]"
              />
            </div>

            {filteredItems.length === 0 ? (
              <div className="px-2 py-6 text-center text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("settings.noSearchMatches")}
              </div>
            ) : (
              <div className="space-y-4">
                {groups.map((grp) => {
                  const items = filteredItems.filter((it) => it.group === grp.id);
                  if (items.length === 0) return null;
                  return (
                    <div key={grp.id} className="space-y-0.5">
                      <div
                        className="px-2.5 pb-1 text-[11px] font-semibold"
                        style={{ color: "var(--pi-ink-tertiary)" }}
                      >
                        {grp.label}
                      </div>
                      {items.map((item) => {
                        const active = item.id === tab;
                        return (
                          <button
                            key={item.id}
                            type="button"
                            onClick={() => onSelectTab(item.id)}
                            className="flex h-8.5 w-full items-center gap-2.5 rounded-[9px] px-2.5 text-left text-[13px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                            style={{
                              background: active ? "var(--pi-wash)" : "transparent",
                              color: active ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
                            }}
                          >
                            <span className="flex shrink-0 items-center justify-center opacity-85">
                              {item.icon}
                            </span>
                            <span className="truncate">{item.label}</span>
                          </button>
                        );
                      })}
                    </div>
                  );
                })}
              </div>
            )}
          </aside>

          <div
            className="flex min-w-0 flex-1 flex-col"
            style={{ background: "var(--pi-surface)" }}
          >
            <div className="flex shrink-0 items-start justify-between gap-3 px-7 pt-6 pb-4">
              <div className="min-w-0">
                <h2
                  className="text-[20px] leading-tight font-semibold tracking-[-0.02em]"
                  style={{ color: "var(--pi-ink)" }}
                >
                  {activeNav.label}
                </h2>
                <p className="mt-1 text-[12.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {activeNav.desc}
                </p>

                <div className="mt-3 flex items-center gap-1 overflow-x-auto sm:hidden">
                  {navItems.map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => onSelectTab(item.id)}
                      className="shrink-0 rounded-md px-2 py-1 text-[12px]"
                      style={{
                        background: item.id === tab ? "var(--pi-wash)" : "transparent",
                        color: item.id === tab ? "var(--pi-ink)" : "var(--pi-ink-tertiary)",
                      }}
                    >
                      {item.label}
                    </button>
                  ))}
                </div>
              </div>

              <DialogPrimitive.Close
                aria-label={t("common.close")}
                className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full transition-colors hover:bg-[var(--pi-hover)]"
                style={{ color: "var(--pi-ink-tertiary)" }}
              >
                <BrandIcon name="navigation/close" size={16} />
              </DialogPrimitive.Close>
            </div>

            <div className="min-h-0 flex-1 overflow-y-auto px-7 pb-8">
              <div className="mx-auto w-full max-w-[680px]">
                {settingsError && (
                  <div className="mb-4">
                    <NoticeBar
                      tone="danger"
                      message={t("settings.saveFailed", { error: settingsError })}
                      action={
                        <Button variant="ghost" size="sm" onClick={clearSettingsError}>
                          {t("common.close")}
                        </Button>
                      }
                    />
                  </div>
                )}
                {tab === "general" && <GeneralSettings />}
                {tab === "appearance" && <AppearanceSettings />}
                {tab === "personalization" && <PersonalizationSettings />}
                {tab === "browser" && <BrowserSettings />}
                {tab === "shortcuts" && <ShortcutsSettings />}
                {tab === "providers" && <ProvidersSettings />}
                {tab === "relay" && <RelaySettings />}
                {tab === "archived" && (
                  <ArchivedSettings
                    onOpenSession={() => {
                      onOpenChange(false);
                      onOpenSession?.();
                    }}
                  />
                )}
                {tab === "diagnostics" && (
                  <DiagnosticsSettings autoCheckUpdateTick={autoCheckUpdateTick} />
                )}
              </div>
            </div>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
