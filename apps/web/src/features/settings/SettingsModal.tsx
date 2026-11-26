import * as DialogPrimitive from "@radix-ui/react-dialog";
import { useEffect } from "react";
import { useI18n } from "../../i18n";
import { useSuperStore } from "../../stores/superStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { BrandIcon } from "../shared/BrandAssets";
import { ArchivedSettings } from "./ArchivedSettings";
import { AutomationsSettings } from "./AutomationsSettings";
import { DiagnosticsSettings } from "./DiagnosticsSettings";
import { GeneralSettings } from "./GeneralSettings";
import { McpSettings } from "./McpSettings";
import { MemorySettings } from "./MemorySettings";
import { ProvidersSettings } from "./ProvidersSettings";
import { RelaySettings } from "./RelaySettings";
import { SkillsSettings } from "./SkillsSettings";

export type SettingsTabId =
  | "general"
  | "providers"
  | "memory"
  | "automations"
  | "skills"
  | "mcp"
  | "relay"
  | "archived"
  | "diagnostics";

export function SettingsModal({
  open,
  tab,
  onSelectTab,
  onOpenChange,
  onOpenSession,
}: {
  open: boolean;
  tab: SettingsTabId;
  onSelectTab: (tab: SettingsTabId) => void;
  onOpenChange: (open: boolean) => void;
  onOpenSession?: (sessionId: string) => void;
}) {
  const { t } = useI18n();
  const unreadInboxCount = useSuperStore((s) => s.unreadInboxCount);

  useEffect(() => {
    if (!open) return undefined;
    useWorkbenchStore.getState().pushOverlay();
    return () => {
      useWorkbenchStore.getState().popOverlay();
    };
  }, [open]);

  const primaryNav: Array<{ id: SettingsTabId; label: string; iconName: string }> = [
    { id: "general", label: t("settings.navGeneral"), iconName: "system/appearance" },
    { id: "providers", label: t("settings.navProviders"), iconName: "ai/model_provider" },
    { id: "relay", label: t("settings.navRelay"), iconName: "system/device_connected" },
    { id: "archived", label: t("settings.navArchived"), iconName: "action/archive" },
  ];

  const capabilityNav: Array<{
    id: SettingsTabId;
    label: string;
    iconName: string;
    badge?: number;
  }> = [
    { id: "memory", label: t("settings.navMemory"), iconName: "ai/memory" },
    {
      id: "automations",
      label: t("settings.navAutomations"),
      iconName: "system/timer",
      badge: unreadInboxCount > 0 ? unreadInboxCount : undefined,
    },
    { id: "skills", label: t("settings.navSkills"), iconName: "ai/skills" },
    { id: "mcp", label: t("settings.navMcp"), iconName: "ai/connectors" },
  ];

  const footerNav: Array<{ id: SettingsTabId; label: string; iconName: string }> = [
    { id: "diagnostics", label: t("settings.aboutDiagnostics"), iconName: "status/info" },
  ];

  const navItems = [...primaryNav, ...capabilityNav, ...footerNav];
  const activeNav = navItems.find((n) => n.id === tab) ?? navItems[0];

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay
          className="fixed inset-0 z-50"
          style={{ background: "var(--pi-scrim)" }}
        />
        <DialogPrimitive.Content
          aria-describedby={undefined}
          className="pi-popover fixed top-1/2 left-1/2 z-50 flex h-[min(680px,88vh)] w-[min(960px,94vw)] -translate-x-1/2 -translate-y-1/2 overflow-hidden rounded-[16px] p-0 outline-none"
        >
          <aside
            className="hidden w-[212px] shrink-0 flex-col justify-between overflow-y-auto p-3 sm:flex"
            style={{
              background: "var(--pi-paper)",
              borderRight: "0.5px solid var(--pi-hairline)",
            }}
          >
            <div className="space-y-1">
              <div className="px-2.5 pt-1 pb-2.5">
                <DialogPrimitive.Title
                  className="text-[14px] font-semibold"
                  style={{ color: "var(--pi-ink)" }}
                >
                  {t("settings.title")}
                </DialogPrimitive.Title>
              </div>

              {primaryNav.map((item) => {
                const active = item.id === tab;
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => onSelectTab(item.id)}
                    className="flex h-9 w-full items-center gap-2.5 rounded-[10px] px-2.5 text-left text-[13px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                    style={{
                      background: active ? "var(--pi-wash)" : "transparent",
                      color: active ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
                    }}
                  >
                    <BrandIcon name={item.iconName} size={15} />
                    <span className="truncate">{item.label}</span>
                  </button>
                );
              })}

              <div className="my-2 h-[0.5px]" style={{ background: "var(--pi-hairline)" }} />

              {capabilityNav.map((item) => {
                const active = item.id === tab;
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => onSelectTab(item.id)}
                    className="flex h-9 w-full items-center gap-2.5 rounded-[10px] px-2.5 text-left text-[13px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                    style={{
                      background: active ? "var(--pi-wash)" : "transparent",
                      color: active ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
                    }}
                  >
                    <BrandIcon name={item.iconName} size={15} />
                    <span className="min-w-0 flex-1 truncate">{item.label}</span>
                    {item.badge && (
                      <span
                        className="rounded-full px-1.5 py-0.5 text-[10px] font-semibold tabular-nums"
                        style={{ background: "var(--pi-accent)", color: "var(--pi-on-accent)" }}
                      >
                        {item.badge}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>

            <div
              className="mt-3 pt-2"
              style={{ borderTop: "0.5px solid var(--pi-hairline)" }}
            >
              {footerNav.map((item) => {
                const active = item.id === tab;
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => onSelectTab(item.id)}
                    className="flex h-9 w-full items-center gap-2.5 rounded-[10px] px-2.5 text-left text-[13px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                    style={{
                      background: active ? "var(--pi-wash)" : "transparent",
                      color: active ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
                    }}
                  >
                    <BrandIcon name={item.iconName} size={15} />
                    <span className="truncate">{item.label}</span>
                  </button>
                );
              })}
            </div>
          </aside>

          <div
            className="flex min-w-0 flex-1 flex-col"
            style={{ background: "var(--pi-surface)" }}
          >
            <div
              className="flex h-[48px] shrink-0 items-center justify-between gap-2 px-5"
              style={{ borderBottom: "0.5px solid var(--pi-hairline)" }}
            >
              <div className="flex items-center gap-2 overflow-x-auto">
                <span className="text-[14.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
                  {activeNav.label}
                </span>
                <div className="ml-2 flex items-center gap-1 sm:hidden">
                  {navItems.map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => onSelectTab(item.id)}
                      className="rounded-md px-2 py-1 text-[12px]"
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

            <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">
              <div className="mx-auto w-full max-w-[700px]">
                {tab === "general" && <GeneralSettings />}
                {tab === "providers" && <ProvidersSettings />}
                {tab === "memory" && <MemorySettings />}
                {tab === "automations" && (
                  <AutomationsSettings onOpenSession={onOpenSession} />
                )}
                {tab === "skills" && <SkillsSettings />}
                {tab === "mcp" && <McpSettings />}
                {tab === "relay" && <RelaySettings />}
                {tab === "archived" && (
                  <ArchivedSettings onOpenSession={() => onOpenChange(false)} />
                )}
                {tab === "diagnostics" && <DiagnosticsSettings />}
              </div>
            </div>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
