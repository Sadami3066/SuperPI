import { create } from "zustand";
import type { HostClient } from "../api/hostClient";
import type {
  HostCustomModelDto,
  HostCustomProviderDto,
  HostProviderDto,
  HostRelayDeviceDto,
  HostRelayStatusDto,
  HostSettingsDto,
} from "../api/types";
import { resolveLocale, type AppLanguagePreference, type ResolvedLocale } from "../i18n";
import { syncNativeLanguage } from "../platform/bridge";
import {
  applyThemeTokens,
  type AppearanceMode,
  type FontStyleId,
  type ThemePresetId,
} from "../platform/theme";

function readStored<T extends string>(key: string, fallback: T): T {
  if (typeof window === "undefined") return fallback;
  try {
    return (window.localStorage.getItem(key) as T) || fallback;
  } catch {
    return fallback;
  }
}

function writeStored(key: string, value: string): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(key, value);
  } catch {
    return;
  }
}

export type BrowserSearchEngine = "google" | "bing" | "duckduckgo" | "baidu";

export interface SettingsStoreState {
  themePreset: ThemePresetId;
  appearance: AppearanceMode;
  fontStyle: FontStyleId;
  customAccent: string | null;
  browserSearchEngine: BrowserSearchEngine;
  languagePref: AppLanguagePreference;
  locale: ResolvedLocale;
  dark: boolean;

  hostSettings: HostSettingsDto | null;
  providers: HostProviderDto[];
  customProviders: HostCustomProviderDto[];
  relayStatus: HostRelayStatusDto | null;
  relayDevices: HostRelayDeviceDto[];
  settingsError: string | null;

  clearSettingsError: () => void;
  applyLocalTheme: () => void;
  setThemePreset: (preset: ThemePresetId) => void;
  setAppearance: (appearance: AppearanceMode) => void;
  setFontStyle: (font: FontStyleId) => void;
  setCustomAccent: (hex: string | null) => void;
  setBrowserSearchEngine: (engine: BrowserSearchEngine) => void;
  resetAppearance: () => void;
  setLanguagePref: (client: HostClient | null, pref: AppLanguagePreference) => Promise<void>;

  refreshSettingsData: (client: HostClient) => Promise<void>;
  refreshRelay: (client: HostClient) => Promise<void>;
  patchHostSettings: (client: HostClient, patch: Record<string, unknown>) => Promise<boolean>;

  saveProviderKey: (client: HostClient, providerId: string, apiKey: string) => Promise<void>;
  clearProviderKey: (client: HostClient, providerId: string) => Promise<void>;
  saveCustomProvider: (
    client: HostClient,
    body: {
      id?: string;
      name: string;
      api: string;
      baseUrl: string;
      apiKey: string;
      models: HostCustomModelDto[];
    },
  ) => Promise<void>;
  deleteCustomProvider: (client: HostClient, id: string) => Promise<void>;
  logoutOAuth: (client: HostClient, providerId: string) => Promise<void>;

  generatePairCode: (client: HostClient) => Promise<{ link: string; expiresAt?: number | null }>;
  pairRelay: (client: HostClient, link: string) => Promise<void>;
  logoutRelay: (client: HostClient) => Promise<void>;
  renameRelayDevice: (client: HostClient, deviceId: string, name: string) => Promise<void>;
  revokeRelayDevice: (client: HostClient, deviceId: string) => Promise<void>;
  setRelayAllowRemote: (client: HostClient, allowRemote: boolean) => Promise<void>;
}

const initialLanguagePref = readStored<AppLanguagePreference>("superpi:language", "system");
const initialCustomAccent = readStored<string>("superpi:customAccent", "") || null;
const initialSearchEngine = readStored<BrowserSearchEngine>(
  "superpi:browserSearchEngine",
  "google",
);

export const useSettingsStore = create<SettingsStoreState>((set, get) => ({
  themePreset: readStored<ThemePresetId>("superpi:themePreset", "pi"),
  appearance: readStored<AppearanceMode>("superpi:appearance", "system"),
  fontStyle: readStored<FontStyleId>("superpi:fontStyle", "manrope"),
  customAccent: initialCustomAccent,
  browserSearchEngine: initialSearchEngine,
  languagePref: initialLanguagePref,
  locale: resolveLocale(initialLanguagePref),
  dark: false,

  hostSettings: null,
  providers: [],
  customProviders: [],
  relayStatus: null,
  relayDevices: [],
  settingsError: null,

  clearSettingsError: () => set({ settingsError: null }),

  applyLocalTheme: () => {
    const { themePreset, appearance, fontStyle, customAccent, hostSettings } = get();
    const dark = applyThemeTokens(themePreset, appearance, fontStyle, customAccent);
    if (typeof document !== "undefined") {
      const scale = (hostSettings?.chatFontScale ?? 100) / 100;
      document.documentElement.style.setProperty("--pi-chat-scale", String(scale));
    }
    set({ dark });
  },

  setThemePreset: (themePreset) => {
    writeStored("superpi:themePreset", themePreset);
    set({ themePreset });
    get().applyLocalTheme();
  },

  setAppearance: (appearance) => {
    writeStored("superpi:appearance", appearance);
    set({ appearance });
    get().applyLocalTheme();
  },

  setFontStyle: (fontStyle) => {
    writeStored("superpi:fontStyle", fontStyle);
    set({ fontStyle });
    get().applyLocalTheme();
  },

  setCustomAccent: (hex) => {
    const clean = hex && /^#[0-9a-fA-F]{6}$/.test(hex.trim()) ? hex.trim() : null;
    writeStored("superpi:customAccent", clean ?? "");
    set({ customAccent: clean });
    get().applyLocalTheme();
  },

  setBrowserSearchEngine: (browserSearchEngine) => {
    writeStored("superpi:browserSearchEngine", browserSearchEngine);
    set({ browserSearchEngine });
  },

  resetAppearance: () => {
    writeStored("superpi:themePreset", "pi");
    writeStored("superpi:appearance", "system");
    writeStored("superpi:fontStyle", "manrope");
    writeStored("superpi:customAccent", "");
    set({
      themePreset: "pi",
      appearance: "system",
      fontStyle: "manrope",
      customAccent: null,
    });
    get().applyLocalTheme();
  },

  setLanguagePref: async (client, pref) => {
    writeStored("superpi:language", pref);
    const locale = resolveLocale(pref);
    set({ languagePref: pref, locale });
    if (typeof document !== "undefined") {
      document.documentElement.lang = locale;
    }
    void syncNativeLanguage(locale);
    if (client) {
      const updated = await client.patchSettings({ language: pref }).catch(() => null);
      if (updated) set({ hostSettings: updated });
    }
  },

  refreshSettingsData: async (client) => {
    const [settings, providers, customProviders, relayStatus, relayDevices] = await Promise.all([
      client.getSettings(),
      client.listProviders().catch(() => [] as HostProviderDto[]),
      client.listCustomProviders().catch(() => [] as HostCustomProviderDto[]),
      client.getRelayStatus().catch(() => null),
      client.listRelayDevices().catch(() => [] as HostRelayDeviceDto[]),
    ]);
    const nextPref =
      settings.language === "zh-CN" || settings.language === "en" || settings.language === "system"
        ? (settings.language as AppLanguagePreference)
        : get().languagePref;
    const locale = resolveLocale(nextPref);
    writeStored("superpi:language", nextPref);
    if (typeof document !== "undefined") {
      document.documentElement.lang = locale;
      document.documentElement.style.setProperty(
        "--pi-chat-scale",
        String((settings.chatFontScale ?? 100) / 100),
      );
    }
    void syncNativeLanguage(locale);
    set({
      hostSettings: settings,
      languagePref: nextPref,
      locale,
      providers,
      customProviders,
      relayStatus,
      relayDevices,
    });
  },

  refreshRelay: async (client) => {
    const [relayStatus, relayDevices] = await Promise.all([
      client.getRelayStatus().catch(() => null),
      client.listRelayDevices().catch(() => [] as HostRelayDeviceDto[]),
    ]);
    set({ relayStatus, relayDevices });
  },

  patchHostSettings: async (client, patch) => {
    set({ settingsError: null });
    try {
      const updated = await client.patchSettings(patch);
      if (typeof document !== "undefined") {
        document.documentElement.style.setProperty(
          "--pi-chat-scale",
          String((updated.chatFontScale ?? 100) / 100),
        );
      }
      set({ hostSettings: updated });
      return true;
    } catch (err) {
      set({ settingsError: err instanceof Error ? err.message : "Failed to save settings" });
      return false;
    }
  },

  saveProviderKey: async (client, providerId, apiKey) => {
    await client.saveProviderKey(providerId, apiKey);
    await get().refreshSettingsData(client);
  },

  clearProviderKey: async (client, providerId) => {
    await client.clearProviderKey(providerId);
    await get().refreshSettingsData(client);
  },

  saveCustomProvider: async (client, body) => {
    await client.saveCustomProvider(body);
    await get().refreshSettingsData(client);
  },

  deleteCustomProvider: async (client, id) => {
    await client.deleteCustomProvider(id);
    await get().refreshSettingsData(client);
  },

  logoutOAuth: async (client, providerId) => {
    await client.logoutOAuth(providerId);
    await get().refreshSettingsData(client);
  },

  generatePairCode: (client) => client.generatePairCode(),

  pairRelay: async (client, link) => {
    const status = await client.pairRelay({ link });
    set({ relayStatus: status });
    await get().refreshRelay(client);
  },

  logoutRelay: async (client) => {
    await client.logoutRelay();
    await get().refreshRelay(client);
  },

  renameRelayDevice: async (client, deviceId, name) => {
    await client.renameRelayDevice(deviceId, name);
    await get().refreshRelay(client);
  },

  revokeRelayDevice: async (client, deviceId) => {
    await client.revokeRelayDevice(deviceId);
    await get().refreshRelay(client);
  },

  setRelayAllowRemote: async (client, allowRemote) => {
    const status = await client.setRelayAllowRemote(allowRemote);
    set({ relayStatus: status });
  },
}));
