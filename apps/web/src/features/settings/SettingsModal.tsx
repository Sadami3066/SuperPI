import * as DialogPrimitive from "@radix-ui/react-dialog";
import { IconX } from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import { BrandIcon } from "../shared/BrandAssets";
import { ArchivedSettings } from "./ArchivedSettings";
import { GeneralSettings } from "./GeneralSettings";
import { ProvidersSettings } from "./ProvidersSettings";
import { RelaySettings } from "./RelaySettings";

export type SettingsTabId = "general" | "providers" | "relay" | "archived";

export function SettingsModal({
  open,
  tab,
  onSelectTab,
  onOpenChange,
}: {
  open: boolean;
  tab: SettingsTabId;
  onSelectTab: (tab: SettingsTabId) => void;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useI18n();

  const navItems: Array<{ id: SettingsTabId; label: string; iconName: string }> = [
    { id: "general", label: t("settings.navGeneral"), iconName: "system/appearance" },
    { id: "providers", label: t("settings.navProviders"), iconName: "ai/model_provider" },
    { id: "relay", label: t("settings.navRelay"), iconName: "system/device_connected" },
    { id: "archived", label: t("settings.navArchived"), iconName: "action/archive" },
  ];

  const activeNav = navItems.find((n) => n.id === tab) ?? navItems[0];

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay
          className="fixed inset-0 z-50 backdrop-blur-[3px]"
          style={{ background: "var(--pi-scrim)" }}
        />
        <DialogPrimitive.Content
          aria-describedby={undefined}
          className="pi-popover fixed top-1/2 left-1/2 z-50 flex h-[min(640px,86vh)] w-[min(880px,92vw)] -translate-x-1/2 -translate-y-1/2 overflow-hidden rounded-[16px] p-0 outline-none"
        >
          <aside
            className="hidden w-[204px] shrink-0 flex-col justify-between p-3 sm:flex"
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

              {navItems.map((item) => {
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
                <IconX size={16} stroke={1.75} />
              </DialogPrimitive.Close>
            </div>

            <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">
              <div className="mx-auto w-full max-w-[600px]">
                {tab === "general" && <GeneralSettings />}
                {tab === "providers" && <ProvidersSettings />}
                {tab === "relay" && <RelaySettings />}
                {tab === "archived" && (
                  <ArchivedSettings onOpenSession={() => onOpenChange(false)} />
                )}
              </div>
            </div>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
