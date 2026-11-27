import type { ComponentType, LazyExoticComponent } from "react";
import type { I18nKey } from "../i18n";

export interface WorkbenchPanelContext {
  botId: string;
  sessionId: string | null;
  isRemote?: boolean;
  activeView?: string;
  tabId?: string;
  isActiveTab?: boolean;
  payload?: Record<string, unknown> | null;
}

export interface WorkbenchPanelDefinition {
  id: string;
  labelKey: I18nKey;
  icon: ComponentType<{ size?: number; stroke?: number }>;
  order: number;
  requiresElectron?: boolean;
  isAvailable?: (ctx: WorkbenchPanelContext) => boolean;
  Component: LazyExoticComponent<ComponentType<{ context: WorkbenchPanelContext }>>;
}

const registry = new Map<string, WorkbenchPanelDefinition>();

export function registerWorkbenchPanel(def: WorkbenchPanelDefinition): void {
  registry.set(def.id, def);
}

export function unregisterWorkbenchPanel(id: string): void {
  registry.delete(id);
}

export function getWorkbenchPanel(id: string): WorkbenchPanelDefinition | null {
  return registry.get(id) ?? null;
}

export function getAvailableWorkbenchPanels(
  ctx: WorkbenchPanelContext,
  isElectron: boolean,
): WorkbenchPanelDefinition[] {
  return Array.from(registry.values())
    .filter((p) => {
      if (p.requiresElectron && !isElectron) return false;
      if (p.isAvailable && !p.isAvailable(ctx)) return false;
      return true;
    })
    .sort((a, b) => a.order - b.order);
}
