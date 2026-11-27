import { syncNativeThemeAppearance } from "./bridge";

export type ThemePresetId =
  | "pi"
  | "paper"
  | "slate"
  | "classic-blue"
  | "santorini"
  | "oceanic";
export type AppearanceMode = "system" | "light" | "dark";
export type FontStyleId = "manrope" | "open-runde" | "lora" | "fredoka" | "system";

export interface SuperPIColorPalette {
  dark: boolean;
  paper: string;
  surface: string;
  raised: string;
  ink: string;
  inkSecondary: string;
  inkTertiary: string;
  accent: string;
  accentPressed: string;
  onAccent: string;
  accentSoft: string;
  danger: string;
  onDanger: string;
  success: string;
  warning: string;
  hairline: string;
  wash: string;
  composer: string;
  scrim: string;
  codeBackground: string;
  inlineCodeBackground: string;
  codeKeyword: string;
  codeString: string;
  codeComment: string;
  codeNumber: string;
  codeFunction: string;
  codeType: string;
}

export interface ThemePresetDefinition {
  id: ThemePresetId;
  nameZh: string;
  nameEn: string;
  fixedMode?: "light" | "dark";
  light: SuperPIColorPalette;
  dark: SuperPIColorPalette;
}

const LIGHT_PI: SuperPIColorPalette = {
  dark: false,
  paper: "#F4F4F2",
  surface: "#FFFFFF",
  raised: "#FFFFFF",
  ink: "#111113",
  inkSecondary: "#3E3E44",
  inkTertiary: "#5A5A62",
  accent: "#141416",
  accentPressed: "#28282C",
  onAccent: "#FAFAFA",
  accentSoft: "#E8E8E5",
  danger: "#C4251D",
  onDanger: "#FFFFFF",
  success: "#0E7342",
  warning: "#A14B08",
  hairline: "#E0E0DC",
  wash: "#E9E9E5",
  composer: "#FFFFFF",
  scrim: "rgba(9, 9, 11, 0.34)",
  codeBackground: "#EFEFED",
  inlineCodeBackground: "#E9E9E6",
  codeKeyword: "#6D28D9",
  codeString: "#15803D",
  codeComment: "#5A5A62",
  codeNumber: "#A14B08",
  codeFunction: "#1D4ED8",
  codeType: "#0E7490",
};

const DARK_PI: SuperPIColorPalette = {
  dark: true,
  paper: "#09090B",
  surface: "#131316",
  raised: "#1B1B20",
  ink: "#F5F5F7",
  inkSecondary: "#C4C4CC",
  inkTertiary: "#9494A0",
  accent: "#F4F4F6",
  accentPressed: "#D4D4DC",
  onAccent: "#09090B",
  accentSoft: "#26262E",
  danger: "#FA776E",
  onDanger: "#1A0808",
  success: "#5CE09B",
  warning: "#FBBF24",
  hairline: "#27272F",
  wash: "#2A2A34",
  composer: "#141418",
  scrim: "rgba(0, 0, 0, 0.6)",
  codeBackground: "#0E0E12",
  inlineCodeBackground: "#202027",
  codeKeyword: "#C4B5FD",
  codeString: "#86EFAC",
  codeComment: "#9494A0",
  codeNumber: "#FCD34D",
  codeFunction: "#93C5FD",
  codeType: "#67E8F9",
};

const LIGHT_PAPER: SuperPIColorPalette = {
  dark: false,
  paper: "#F4F1EB",
  surface: "#FCFBF8",
  raised: "#FFFFFF",
  ink: "#191715",
  inkSecondary: "#47423B",
  inkTertiary: "#665F56",
  accent: "#A84323",
  accentPressed: "#8D361B",
  onAccent: "#FFFDF9",
  accentSoft: "#F3E4DC",
  danger: "#B91C1C",
  onDanger: "#FFFFFF",
  success: "#166D45",
  warning: "#9A4A06",
  hairline: "#DCD5C9",
  wash: "#E8E3D9",
  composer: "#FFFFFF",
  scrim: "rgba(25, 23, 21, 0.34)",
  codeBackground: "#ECE7DE",
  inlineCodeBackground: "#EAE4DA",
  codeKeyword: "#7C3AED",
  codeString: "#166D45",
  codeComment: "#665F56",
  codeNumber: "#9A4A06",
  codeFunction: "#A84323",
  codeType: "#0F766E",
};

const DARK_PAPER: SuperPIColorPalette = {
  dark: true,
  paper: "#11100F",
  surface: "#1C1A17",
  raised: "#25221E",
  ink: "#F5F1EA",
  inkSecondary: "#C7BFB4",
  inkTertiary: "#9D9489",
  accent: "#E88862",
  accentPressed: "#F09B7A",
  onAccent: "#170E0A",
  accentSoft: "#35241D",
  danger: "#FA786E",
  onDanger: "#1A0808",
  success: "#6EE0A0",
  warning: "#F7BC46",
  hairline: "#36312B",
  wash: "#312D28",
  composer: "#1C1A17",
  scrim: "rgba(0, 0, 0, 0.6)",
  codeBackground: "#161412",
  inlineCodeBackground: "#292520",
  codeKeyword: "#D4B5FA",
  codeString: "#8EE3AF",
  codeComment: "#9D9489",
  codeNumber: "#F7BC46",
  codeFunction: "#E88862",
  codeType: "#7DD3C7",
};

const LIGHT_SLATE: SuperPIColorPalette = {
  dark: false,
  paper: "#F1F4F8",
  surface: "#FFFFFF",
  raised: "#FFFFFF",
  ink: "#0F172A",
  inkSecondary: "#334155",
  inkTertiary: "#526275",
  accent: "#1D4ED8",
  accentPressed: "#1E40AF",
  onAccent: "#FFFFFF",
  accentSoft: "#E2ECFD",
  danger: "#C4251D",
  onDanger: "#FFFFFF",
  success: "#0F7347",
  warning: "#A14B08",
  hairline: "#D6DFE9",
  wash: "#E4EAF1",
  composer: "#FFFFFF",
  scrim: "rgba(15, 23, 42, 0.34)",
  codeBackground: "#EAEFF5",
  inlineCodeBackground: "#E6ECF3",
  codeKeyword: "#6D28D9",
  codeString: "#0F7347",
  codeComment: "#526275",
  codeNumber: "#A14B08",
  codeFunction: "#1D4ED8",
  codeType: "#0E7490",
};

const DARK_SLATE: SuperPIColorPalette = {
  dark: true,
  paper: "#0C131B",
  surface: "#141F2B",
  raised: "#1D2B3A",
  ink: "#F1F5F9",
  inkSecondary: "#C0CFE0",
  inkTertiary: "#94A8BD",
  accent: "#70B0FF",
  accentPressed: "#8EC1FF",
  onAccent: "#081320",
  accentSoft: "#1E334B",
  danger: "#FF7A7A",
  onDanger: "#1A0808",
  success: "#6BE2A5",
  warning: "#FBC449",
  hairline: "#2D4055",
  wash: "#27384B",
  composer: "#141F2B",
  scrim: "rgba(0, 0, 0, 0.6)",
  codeBackground: "#0F1822",
  inlineCodeBackground: "#213040",
  codeKeyword: "#C4B5FD",
  codeString: "#86EFAC",
  codeComment: "#94A8BD",
  codeNumber: "#FBC449",
  codeFunction: "#70B0FF",
  codeType: "#67E8F9",
};

const LIGHT_CLASSIC_BLUE: SuperPIColorPalette = {
  dark: false,
  paper: "#F7F8F8",
  surface: "#FFFFFF",
  raised: "#FFFFFF",
  ink: "#0B1220",
  inkSecondary: "#354451",
  inkTertiary: "#56636E",
  accent: "#1D4ED8",
  accentPressed: "#1E40AF",
  onAccent: "#FFFFFF",
  accentSoft: "#E8F0FE",
  danger: "#C4251D",
  onDanger: "#FFFFFF",
  success: "#0E7342",
  warning: "#A14B08",
  hairline: "#D6E0E3",
  wash: "#EDF3F4",
  composer: "#FFFFFF",
  scrim: "rgba(11, 18, 32, 0.32)",
  codeBackground: "#F0F3F5",
  inlineCodeBackground: "#EDF1F4",
  codeKeyword: "#6D28D9",
  codeString: "#15803D",
  codeComment: "#56636E",
  codeNumber: "#A14B08",
  codeFunction: "#1D4ED8",
  codeType: "#0E7490",
};

const LIGHT_SANTORINI: SuperPIColorPalette = {
  dark: false,
  paper: "#F7F8F8",
  surface: "#FFFFFF",
  raised: "#FFFFFF",
  ink: "#1E3A47",
  inkSecondary: "#3F5E6C",
  inkTertiary: "#566A78",
  accent: "#0067B8",
  accentPressed: "#005294",
  onAccent: "#FFFFFF",
  accentSoft: "#E3F2FD",
  danger: "#B3261E",
  onDanger: "#FFFFFF",
  success: "#186A48",
  warning: "#9A5600",
  hairline: "#DCE2E5",
  wash: "#EEF2F4",
  composer: "#FFFFFF",
  scrim: "rgba(30, 58, 71, 0.32)",
  codeBackground: "#EFF4F7",
  inlineCodeBackground: "#EEF2F4",
  codeKeyword: "#005A9E",
  codeString: "#186A48",
  codeComment: "#566A78",
  codeNumber: "#9A5600",
  codeFunction: "#0067B8",
  codeType: "#0E7490",
};

const DARK_OCEANIC: SuperPIColorPalette = {
  dark: true,
  paper: "#1B2B34",
  surface: "#22343E",
  raised: "#2A3F4B",
  ink: "#F4F7F8",
  inkSecondary: "#C0CED6",
  inkTertiary: "#9CB2BF",
  accent: "#7CB2E8",
  accentPressed: "#94C3F0",
  onAccent: "#0B1A22",
  accentSoft: "#22384A",
  danger: "#FF7A80",
  onDanger: "#1A0808",
  success: "#99C794",
  warning: "#FAC863",
  hairline: "#324A57",
  wash: "#344E5D",
  composer: "#22343E",
  scrim: "rgba(0, 0, 0, 0.6)",
  codeBackground: "#132028",
  inlineCodeBackground: "#263A45",
  codeKeyword: "#C594C5",
  codeString: "#99C794",
  codeComment: "#8C9BA6",
  codeNumber: "#FAC863",
  codeFunction: "#7CB2E8",
  codeType: "#5FB3B3",
};

export const THEME_PRESETS: Record<ThemePresetId, ThemePresetDefinition> = {
  pi: {
    id: "pi",
    nameZh: "PI 黑白",
    nameEn: "PI Mono",
    light: LIGHT_PI,
    dark: DARK_PI,
  },
  paper: {
    id: "paper",
    nameZh: "纸墨",
    nameEn: "Paper Ink",
    light: LIGHT_PAPER,
    dark: DARK_PAPER,
  },
  slate: {
    id: "slate",
    nameZh: "石板",
    nameEn: "Slate",
    light: LIGHT_SLATE,
    dark: DARK_SLATE,
  },
  "classic-blue": {
    id: "classic-blue",
    nameZh: "经典蓝",
    nameEn: "Classic Blue",
    fixedMode: "light",
    light: LIGHT_CLASSIC_BLUE,
    dark: DARK_SLATE,
  },
  santorini: {
    id: "santorini",
    nameZh: "圣托里尼",
    nameEn: "Santorini",
    fixedMode: "light",
    light: LIGHT_SANTORINI,
    dark: DARK_OCEANIC,
  },
  oceanic: {
    id: "oceanic",
    nameZh: "深海",
    nameEn: "Oceanic",
    fixedMode: "dark",
    light: LIGHT_SANTORINI,
    dark: DARK_OCEANIC,
  },
};

export const FONT_STYLES: Array<{
  id: FontStyleId;
  nameZh: string;
  nameEn: string;
  stack: string;
}> = [
  {
    id: "manrope",
    nameZh: "Manrope",
    nameEn: "Manrope",
    stack: '"Manrope", "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif',
  },
  {
    id: "open-runde",
    nameZh: "Open Runde",
    nameEn: "Open Runde",
    stack: '"Open Runde", "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif',
  },
  {
    id: "lora",
    nameZh: "Lora",
    nameEn: "Lora",
    stack: '"Lora", "Songti SC", "Noto Serif CJK SC", Georgia, serif',
  },
  {
    id: "fredoka",
    nameZh: "Fredoka",
    nameEn: "Fredoka",
    stack: '"Fredoka", "PingFang SC", "Noto Sans CJK SC", sans-serif',
  },
  {
    id: "system",
    nameZh: "跟随系统",
    nameEn: "System",
    stack: 'system-ui, -apple-system, "PingFang SC", "Noto Sans CJK SC", sans-serif',
  },
];

export function resolveThemePalette(
  preset: ThemePresetId,
  appearance: AppearanceMode,
): SuperPIColorPalette {
  const def = THEME_PRESETS[preset] ?? THEME_PRESETS.pi;
  if (def.fixedMode === "light") return def.light;
  if (def.fixedMode === "dark") return def.dark;
  const prefersDark =
    typeof window !== "undefined" &&
    window.matchMedia?.("(prefers-color-scheme: dark)").matches;
  const dark = appearance === "system" ? prefersDark : appearance === "dark";
  return dark ? def.dark : def.light;
}

function parseHexColor(hex: string): [number, number, number] | null {
  const clean = hex.trim();
  if (!/^#[0-9a-fA-F]{6}$/.test(clean)) return null;
  return [
    parseInt(clean.slice(1, 3), 16),
    parseInt(clean.slice(3, 5), 16),
    parseInt(clean.slice(5, 7), 16),
  ];
}

function relativeLuminance(r: number, g: number, b: number): number {
  const norm = [r, g, b].map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * norm[0] + 0.7152 * norm[1] + 0.0722 * norm[2];
}

function adjustChannel(c: number, delta: number): string {
  return Math.max(0, Math.min(255, Math.round(c + delta)))
    .toString(16)
    .padStart(2, "0");
}

function deriveAccentTokens(hex: string, dark: boolean): {
  accent: string;
  accentPressed: string;
  onAccent: string;
  accentSoft: string;
} | null {
  const rgb = parseHexColor(hex);
  if (!rgb) return null;
  const [r, g, b] = rgb;
  const lum = relativeLuminance(r, g, b);
  const delta = dark ? 22 : -22;
  return {
    accent: hex.trim(),
    accentPressed: `#${adjustChannel(r, delta)}${adjustChannel(g, delta)}${adjustChannel(b, delta)}`,
    onAccent: lum > 0.42 ? "#111113" : "#FFFFFF",
    accentSoft: `color-mix(in srgb, ${hex.trim()} ${dark ? 22 : 14}%, transparent)`,
  };
}

export function applyThemeTokens(
  preset: ThemePresetId,
  appearance: AppearanceMode,
  font: FontStyleId,
  customAccent?: string | null,
): boolean {
  const palette = resolveThemePalette(preset, appearance);
  const dark = palette.dark;
  if (typeof document === "undefined") return dark;

  const accentOverride = customAccent ? deriveAccentTokens(customAccent, dark) : null;
  const root = document.documentElement;
  root.classList.toggle("pi-dark", dark);
  root.style.colorScheme = dark ? "dark" : "light";

  const vars: Record<string, string> = {
    "--pi-paper": palette.paper,
    "--pi-surface": palette.surface,
    "--pi-raised": palette.raised,
    "--pi-ink": palette.ink,
    "--pi-ink-secondary": palette.inkSecondary,
    "--pi-ink-tertiary": palette.inkTertiary,
    "--pi-accent": accentOverride?.accent ?? palette.accent,
    "--pi-accent-pressed": accentOverride?.accentPressed ?? palette.accentPressed,
    "--pi-on-accent": accentOverride?.onAccent ?? palette.onAccent,
    "--pi-accent-soft": accentOverride?.accentSoft ?? palette.accentSoft,
    "--pi-danger": palette.danger,
    "--pi-on-danger": palette.onDanger,
    "--pi-success": palette.success,
    "--pi-warning": palette.warning,
    "--pi-hairline": palette.hairline,
    "--pi-wash": palette.wash,
    "--pi-hover": dark ? "rgba(255,255,255,0.07)" : "rgba(0,0,0,0.04)",
    "--pi-composer": palette.composer,
    "--pi-scrim": palette.scrim,
    "--pi-surface-rim": dark ? "rgba(255,255,255,0.09)" : `${palette.hairline}99`,
    "--pi-composer-shadow": dark
      ? "0 12px 32px -12px rgba(0, 0, 0, 0.58), 0 2px 6px rgba(0, 0, 0, 0.28)"
      : "0 10px 28px -10px rgba(11, 18, 32, 0.12), 0 2px 6px rgba(11, 18, 32, 0.04)",
    "--pi-popover-shadow": dark
      ? "0 18px 42px -12px rgba(0, 0, 0, 0.72), 0 2px 8px rgba(0, 0, 0, 0.4)"
      : "0 16px 40px -12px rgba(11, 18, 32, 0.18), 0 2px 8px rgba(11, 18, 32, 0.06)",
    "--pi-code-bg": palette.codeBackground,
    "--pi-inline-code-bg": palette.inlineCodeBackground,
    "--pi-code-keyword": palette.codeKeyword,
    "--pi-code-string": palette.codeString,
    "--pi-code-comment": palette.codeComment,
    "--pi-code-number": palette.codeNumber,
    "--pi-code-function": palette.codeFunction,
    "--pi-code-type": palette.codeType,
    "--pi-font-sans": FONT_STYLES.find((f) => f.id === font)?.stack ?? FONT_STYLES[0].stack,
  };

  for (const [k, v] of Object.entries(vars)) {
    root.style.setProperty(k, v);
  }
  void syncNativeThemeAppearance(dark, palette.paper, palette.ink);
  return dark;
}
