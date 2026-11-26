import { createContext, useContext } from "react";
import { en } from "./en";
import { zhCN, type I18nKey } from "./zh-CN";

export type AppLanguagePreference = "system" | "zh-CN" | "en";
export type ResolvedLocale = "zh-CN" | "en";

export function resolveLocale(pref: AppLanguagePreference): ResolvedLocale {
  if (pref === "en") return "en";
  if (pref === "zh-CN") return "zh-CN";
  if (typeof navigator !== "undefined" && navigator.language.toLowerCase().startsWith("en")) {
    return "en";
  }
  return "zh-CN";
}

export function translate(
  locale: ResolvedLocale,
  key: I18nKey,
  params?: Record<string, string | number>,
): string {
  const dict = locale === "en" ? en : zhCN;
  let template: string = dict[key] ?? zhCN[key] ?? key;
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      template = template.replaceAll(`{${k}}`, String(v));
    }
  }
  return template;
}

export const I18nContext = createContext<{
  locale: ResolvedLocale;
  t: (key: I18nKey, params?: Record<string, string | number>) => string;
}>({
  locale: "zh-CN",
  t: (key, params) => translate("zh-CN", key, params),
});

export function useI18n() {
  return useContext(I18nContext);
}

export type { I18nKey };
