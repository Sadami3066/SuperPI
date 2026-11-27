import { syncNativeThemeAppearance } from "./bridge";

export type ThemePresetId =
  | "pi"
  | "tokyo-night"
  | "night-owl"
  | "catppuccin"
  | "nord"
  | "rose-pine"
  | "gruvbox"
  | "dracula"
  | "solarized";
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
  userBubble: string;
  userBubbleBorder: string;
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

// 1. PI Mono — Crisp studio neutral monochrome
const LIGHT_PI: SuperPIColorPalette = {
  dark: false,
  paper: "#F5F6F8",
  surface: "#FFFFFF",
  raised: "#FFFFFF",
  ink: "#141417",
  inkSecondary: "#3F414A",
  inkTertiary: "#636672",
  accent: "#18181B",
  accentPressed: "#2D2D33",
  onAccent: "#FAFAFA",
  accentSoft: "#ECEEF2",
  userBubble: "#F2F4F7",
  userBubbleBorder: "#E3E6EC",
  danger: "#C4251D",
  onDanger: "#FFFFFF",
  success: "#0E7342",
  warning: "#A14B08",
  hairline: "#E4E6EB",
  wash: "#ECEEF2",
  composer: "#FFFFFF",
  scrim: "rgba(9, 9, 11, 0.34)",
  codeBackground: "#F4F5F7",
  inlineCodeBackground: "#ECEEF2",
  codeKeyword: "#6D28D9",
  codeString: "#15803D",
  codeComment: "#636672",
  codeNumber: "#A14B08",
  codeFunction: "#1D4ED8",
  codeType: "#0E7490",
};

const DARK_PI: SuperPIColorPalette = {
  dark: true,
  paper: "#0D0E12",
  surface: "#14161B",
  raised: "#1C1E25",
  ink: "#F4F5F7",
  inkSecondary: "#C5C8D1",
  inkTertiary: "#9398A6",
  accent: "#F4F5F7",
  accentPressed: "#D6D8E0",
  onAccent: "#09090B",
  accentSoft: "#252832",
  userBubble: "#22252F",
  userBubbleBorder: "#303443",
  danger: "#FA776E",
  onDanger: "#1A0808",
  success: "#5CE09B",
  warning: "#FBBF24",
  hairline: "#262933",
  wash: "#232630",
  composer: "#1A1C23",
  scrim: "rgba(0, 0, 0, 0.62)",
  codeBackground: "#101217",
  inlineCodeBackground: "#21242D",
  codeKeyword: "#C4B5FD",
  codeString: "#86EFAC",
  codeComment: "#8E94A4",
  codeNumber: "#FCD34D",
  codeFunction: "#93C5FD",
  codeType: "#67E8F9",
};

// 2. Tokyo Night — Iconic VS Code Tokyo Night Day & Storm/Night
const LIGHT_TOKYO_NIGHT: SuperPIColorPalette = {
  dark: false,
  paper: "#E9ECF3",
  surface: "#F6F7FB",
  raised: "#FFFFFF",
  ink: "#262D47",
  inkSecondary: "#454E6E",
  inkTertiary: "#616C8F",
  accent: "#2E6BE6",
  accentPressed: "#2356BE",
  onAccent: "#FFFFFF",
  accentSoft: "#DFEAFF",
  userBubble: "#E8EFFD",
  userBubbleBorder: "#CEDDFB",
  danger: "#C93B4E",
  onDanger: "#FFFFFF",
  success: "#2F7A4D",
  warning: "#996012",
  hairline: "#D5DBE8",
  wash: "#E2E6F0",
  composer: "#FFFFFF",
  scrim: "rgba(26, 31, 51, 0.34)",
  codeBackground: "#ECEFF7",
  inlineCodeBackground: "#E4E8F3",
  codeKeyword: "#7847BD",
  codeString: "#387068",
  codeComment: "#667194",
  codeNumber: "#B15C00",
  codeFunction: "#2E6BE6",
  codeType: "#007197",
};

const DARK_TOKYO_NIGHT: SuperPIColorPalette = {
  dark: true,
  paper: "#16161E",
  surface: "#1A1B26",
  raised: "#24283B",
  ink: "#C9D3F5",
  inkSecondary: "#A9B1D6",
  inkTertiary: "#7A84A8",
  accent: "#7AA2F7",
  accentPressed: "#94B5FA",
  onAccent: "#101423",
  accentSoft: "#252E4A",
  userBubble: "#252A41",
  userBubbleBorder: "#343B59",
  danger: "#F7768E",
  onDanger: "#1A0A0E",
  success: "#9ECE6A",
  warning: "#E0AF68",
  hairline: "#292E42",
  wash: "#23283B",
  composer: "#1F2335",
  scrim: "rgba(9, 10, 18, 0.68)",
  codeBackground: "#14151F",
  inlineCodeBackground: "#24283B",
  codeKeyword: "#BB9AF7",
  codeString: "#9ECE6A",
  codeComment: "#7A84A8",
  codeNumber: "#FF9E64",
  codeFunction: "#7AA2F7",
  codeType: "#2AC3DE",
};

// 3. Night Owl — Sarah Drasner's Light Owl & Night Owl
const LIGHT_NIGHT_OWL: SuperPIColorPalette = {
  dark: false,
  paper: "#EEF2F5",
  surface: "#FBFBFB",
  raised: "#FFFFFF",
  ink: "#263238",
  inkSecondary: "#40525C",
  inkTertiary: "#5C707C",
  accent: "#0C868B",
  accentPressed: "#096D71",
  onAccent: "#FFFFFF",
  accentSoft: "#DCF2F2",
  userBubble: "#E8F4F5",
  userBubbleBorder: "#CBE6E8",
  danger: "#C62828",
  onDanger: "#FFFFFF",
  success: "#2E7D32",
  warning: "#A65A00",
  hairline: "#D7E1E7",
  wash: "#E5ECF0",
  composer: "#FFFFFF",
  scrim: "rgba(1, 22, 39, 0.34)",
  codeBackground: "#F0F4F7",
  inlineCodeBackground: "#E6EEF2",
  codeKeyword: "#8B39A8",
  codeString: "#9F4E1E",
  codeComment: "#637777",
  codeNumber: "#AA0982",
  codeFunction: "#3B66C4",
  codeType: "#0C868B",
};

const DARK_NIGHT_OWL: SuperPIColorPalette = {
  dark: true,
  paper: "#01111D",
  surface: "#011627",
  raised: "#0B253A",
  ink: "#D6DEEB",
  inkSecondary: "#A8C0D6",
  inkTertiary: "#7B98B2",
  accent: "#82AAFF",
  accentPressed: "#9CBEFF",
  onAccent: "#01111D",
  accentSoft: "#14304C",
  userBubble: "#0D2940",
  userBubbleBorder: "#1A3E5C",
  danger: "#EF5350",
  onDanger: "#1A0808",
  success: "#ADDB67",
  warning: "#ECC48D",
  hairline: "#143047",
  wash: "#0D263C",
  composer: "#071E31",
  scrim: "rgba(0, 8, 16, 0.72)",
  codeBackground: "#010E1A",
  inlineCodeBackground: "#0B253A",
  codeKeyword: "#C792EA",
  codeString: "#ECC48D",
  codeComment: "#6B8B9E",
  codeNumber: "#F78C6C",
  codeFunction: "#82AAFF",
  codeType: "#7FDBCA",
};

// 4. Catppuccin — Soothing Pastel Latte & Mocha
const LIGHT_CATPPUCCIN: SuperPIColorPalette = {
  dark: false,
  paper: "#E6E9EF",
  surface: "#F4F6FA",
  raised: "#FFFFFF",
  ink: "#303446",
  inkSecondary: "#4C4F69",
  inkTertiary: "#6C6F85",
  accent: "#7C3AED",
  accentPressed: "#6828D6",
  onAccent: "#FFFFFF",
  accentSoft: "#ECE3FC",
  userBubble: "#EFE9FB",
  userBubbleBorder: "#DDD2F6",
  danger: "#C81E45",
  onDanger: "#FFFFFF",
  success: "#2D8A3E",
  warning: "#B45309",
  hairline: "#D2D7E2",
  wash: "#DFE3EC",
  composer: "#FFFFFF",
  scrim: "rgba(48, 52, 70, 0.34)",
  codeBackground: "#ECEFF5",
  inlineCodeBackground: "#E3E7F0",
  codeKeyword: "#8839EF",
  codeString: "#40A02B",
  codeComment: "#6C6F85",
  codeNumber: "#D05A0B",
  codeFunction: "#1E66F5",
  codeType: "#179299",
};

const DARK_CATPPUCCIN: SuperPIColorPalette = {
  dark: true,
  paper: "#181825",
  surface: "#1E1E2E",
  raised: "#27273B",
  ink: "#CDD6F4",
  inkSecondary: "#BAC2DE",
  inkTertiary: "#8E95B2",
  accent: "#CBA6F7",
  accentPressed: "#D8BCFA",
  onAccent: "#181825",
  accentSoft: "#2E2744",
  userBubble: "#2B2840",
  userBubbleBorder: "#3D3859",
  danger: "#F38BA8",
  onDanger: "#1A0A10",
  success: "#A6E3A1",
  warning: "#F9E2AF",
  hairline: "#313244",
  wash: "#2A2B3D",
  composer: "#232336",
  scrim: "rgba(12, 12, 20, 0.68)",
  codeBackground: "#141421",
  inlineCodeBackground: "#2A2B3D",
  codeKeyword: "#CBA6F7",
  codeString: "#A6E3A1",
  codeComment: "#8E95B2",
  codeNumber: "#FAB387",
  codeFunction: "#89B4FA",
  codeType: "#94E2D5",
};

// 5. Nord — Arctic Snow Storm & Polar Night
const LIGHT_NORD: SuperPIColorPalette = {
  dark: false,
  paper: "#ECEFF4",
  surface: "#F8FAFC",
  raised: "#FFFFFF",
  ink: "#242933",
  inkSecondary: "#3B4252",
  inkTertiary: "#58637A",
  accent: "#3B6E9C",
  accentPressed: "#2E5A82",
  onAccent: "#FFFFFF",
  accentSoft: "#DFEBF5",
  userBubble: "#E8F0F7",
  userBubbleBorder: "#D0DFEE",
  danger: "#B93746",
  onDanger: "#FFFFFF",
  success: "#3F7A4D",
  warning: "#9B6215",
  hairline: "#D8DEE9",
  wash: "#E5E9F0",
  composer: "#FFFFFF",
  scrim: "rgba(36, 41, 51, 0.34)",
  codeBackground: "#EFF2F7",
  inlineCodeBackground: "#E6EBF3",
  codeKeyword: "#5E81AC",
  codeString: "#5D874B",
  codeComment: "#616E88",
  codeNumber: "#9B5E8D",
  codeFunction: "#3B6E9C",
  codeType: "#4A8B9B",
};

const DARK_NORD: SuperPIColorPalette = {
  dark: true,
  paper: "#242933",
  surface: "#2E3440",
  raised: "#38404F",
  ink: "#ECEFF4",
  inkSecondary: "#D8DEE9",
  inkTertiary: "#9AA7BE",
  accent: "#88C0D0",
  accentPressed: "#9FD0DE",
  onAccent: "#1B222C",
  accentSoft: "#354656",
  userBubble: "#374151",
  userBubbleBorder: "#485468",
  danger: "#DF7580",
  onDanger: "#1A0A0C",
  success: "#A3BE8C",
  warning: "#EBCB8B",
  hairline: "#3E4656",
  wash: "#3B4252",
  composer: "#333A48",
  scrim: "rgba(16, 20, 26, 0.66)",
  codeBackground: "#222730",
  inlineCodeBackground: "#3B4252",
  codeKeyword: "#81A1C1",
  codeString: "#A3BE8C",
  codeComment: "#8E9BB2",
  codeNumber: "#B48EAD",
  codeFunction: "#88C0D0",
  codeType: "#8FBCBB",
};

// 6. Rosé Pine — Soho Vibes Dawn & Main
const LIGHT_ROSE_PINE: SuperPIColorPalette = {
  dark: false,
  paper: "#F2E9E1",
  surface: "#FAF4ED",
  raised: "#FFFDF9",
  ink: "#3E3859",
  inkSecondary: "#575279",
  inkTertiary: "#766F8E",
  accent: "#B44E6B",
  accentPressed: "#973D57",
  onAccent: "#FFFDF9",
  accentSoft: "#F4E1E6",
  userBubble: "#F4E9E6",
  userBubbleBorder: "#E5D3D0",
  danger: "#B43757",
  onDanger: "#FFFFFF",
  success: "#286983",
  warning: "#A6631B",
  hairline: "#E4D9CE",
  wash: "#EDE2D8",
  composer: "#FFFDF9",
  scrim: "rgba(42, 39, 63, 0.34)",
  codeBackground: "#F3EAE1",
  inlineCodeBackground: "#EDE2D8",
  codeKeyword: "#286983",
  codeString: "#B3681B",
  codeComment: "#766F8E",
  codeNumber: "#907AA9",
  codeFunction: "#B44E6B",
  codeType: "#56949F",
};

const DARK_ROSE_PINE: SuperPIColorPalette = {
  dark: true,
  paper: "#191724",
  surface: "#1F1D2E",
  raised: "#28253A",
  ink: "#E0DEF4",
  inkSecondary: "#C4C0DB",
  inkTertiary: "#948FA9",
  accent: "#C4A7E7",
  accentPressed: "#D3BDEE",
  onAccent: "#191724",
  accentSoft: "#322A44",
  userBubble: "#2D283E",
  userBubbleBorder: "#403A58",
  danger: "#EB6F92",
  onDanger: "#1A0A10",
  success: "#9CCFD8",
  warning: "#F6C177",
  hairline: "#332E47",
  wash: "#2A273F",
  composer: "#242136",
  scrim: "rgba(12, 10, 18, 0.7)",
  codeBackground: "#151320",
  inlineCodeBackground: "#2A273F",
  codeKeyword: "#3E8FB0",
  codeString: "#F6C177",
  codeComment: "#908CAA",
  codeNumber: "#C4A7E7",
  codeFunction: "#EBBCBA",
  codeType: "#9CCFD8",
};

// 7. Gruvbox — Retro Warm Groove Light & Dark
const LIGHT_GRUVBOX: SuperPIColorPalette = {
  dark: false,
  paper: "#F0E5C9",
  surface: "#FBF5E2",
  raised: "#FFFDF5",
  ink: "#282828",
  inkSecondary: "#504945",
  inkTertiary: "#6E6359",
  accent: "#AF3A03",
  accentPressed: "#8F2F02",
  onAccent: "#FFFDF5",
  accentSoft: "#F3DFC8",
  userBubble: "#F2E6CD",
  userBubbleBorder: "#DFCEA9",
  danger: "#9D0006",
  onDanger: "#FFFFFF",
  success: "#5E6B0C",
  warning: "#A36808",
  hairline: "#DDD0B1",
  wash: "#EAE0C3",
  composer: "#FFFDF5",
  scrim: "rgba(40, 40, 40, 0.36)",
  codeBackground: "#F2E8CE",
  inlineCodeBackground: "#E9DEC2",
  codeKeyword: "#9D0006",
  codeString: "#6C740E",
  codeComment: "#73695F",
  codeNumber: "#8F3F71",
  codeFunction: "#AF3A03",
  codeType: "#076678",
};

const DARK_GRUVBOX: SuperPIColorPalette = {
  dark: true,
  paper: "#1D2021",
  surface: "#282828",
  raised: "#32302F",
  ink: "#EBDBB2",
  inkSecondary: "#D5C4A1",
  inkTertiary: "#A89984",
  accent: "#FE8019",
  accentPressed: "#FF9944",
  onAccent: "#1D2021",
  accentSoft: "#3E2D1E",
  userBubble: "#37322E",
  userBubbleBorder: "#4B443E",
  danger: "#FB4934",
  onDanger: "#1A0808",
  success: "#B8BB26",
  warning: "#FABD2F",
  hairline: "#3C3836",
  wash: "#363331",
  composer: "#2E2C2B",
  scrim: "rgba(12, 12, 12, 0.68)",
  codeBackground: "#1B1E1F",
  inlineCodeBackground: "#363331",
  codeKeyword: "#FB4934",
  codeString: "#B8BB26",
  codeComment: "#A89984",
  codeNumber: "#D3869B",
  codeFunction: "#FE8019",
  codeType: "#8EC07C",
};

// 8. Dracula — Crisp Violet Alucard Light & Classic Dracula Dark
const LIGHT_DRACULA: SuperPIColorPalette = {
  dark: false,
  paper: "#F0EEF6",
  surface: "#FAF9FD",
  raised: "#FFFFFF",
  ink: "#232136",
  inkSecondary: "#45425E",
  inkTertiary: "#656180",
  accent: "#7C3AED",
  accentPressed: "#6624D9",
  onAccent: "#FFFFFF",
  accentSoft: "#EDE5FD",
  userBubble: "#F0EAFD",
  userBubbleBorder: "#DDD1FA",
  danger: "#C92A4C",
  onDanger: "#FFFFFF",
  success: "#1C7C47",
  warning: "#A65A00",
  hairline: "#DDD9EA",
  wash: "#E9E5F4",
  composer: "#FFFFFF",
  scrim: "rgba(33, 34, 44, 0.34)",
  codeBackground: "#F1EEF8",
  inlineCodeBackground: "#EAE6F5",
  codeKeyword: "#B82574",
  codeString: "#227A4B",
  codeComment: "#656180",
  codeNumber: "#7C3AED",
  codeFunction: "#1D6FA5",
  codeType: "#0E7490",
};

const DARK_DRACULA: SuperPIColorPalette = {
  dark: true,
  paper: "#21222C",
  surface: "#282A36",
  raised: "#323546",
  ink: "#F8F8F2",
  inkSecondary: "#D4D6E4",
  inkTertiary: "#959EC0",
  accent: "#BD93F9",
  accentPressed: "#CEA9FB",
  onAccent: "#1B1C24",
  accentSoft: "#382F52",
  userBubble: "#353749",
  userBubbleBorder: "#484B63",
  danger: "#FF5555",
  onDanger: "#1A0808",
  success: "#50FA7B",
  warning: "#F1FA8C",
  hairline: "#3E4156",
  wash: "#343746",
  composer: "#2E303E",
  scrim: "rgba(14, 15, 22, 0.7)",
  codeBackground: "#1D1E28",
  inlineCodeBackground: "#343746",
  codeKeyword: "#FF79C6",
  codeString: "#F1FA8C",
  codeComment: "#8E98BD",
  codeNumber: "#BD93F9",
  codeFunction: "#50FA7B",
  codeType: "#8BE9FD",
};

// 9. Solarized — Ethan Schoonover Precision Light & Dark
const LIGHT_SOLARIZED: SuperPIColorPalette = {
  dark: false,
  paper: "#EEE8D5",
  surface: "#FDF6E3",
  raised: "#FFFDF6",
  ink: "#073642",
  inkSecondary: "#3B5962",
  inkTertiary: "#586E75",
  accent: "#1F74B3",
  accentPressed: "#175C8F",
  onAccent: "#FFFFFF",
  accentSoft: "#DDEAF2",
  userBubble: "#EAE4CF",
  userBubbleBorder: "#D8CFB4",
  danger: "#C92A26",
  onDanger: "#FFFFFF",
  success: "#5B7800",
  warning: "#996A00",
  hairline: "#DCD5BE",
  wash: "#E8E1CC",
  composer: "#FFFDF6",
  scrim: "rgba(0, 43, 54, 0.34)",
  codeBackground: "#F2ECDA",
  inlineCodeBackground: "#EAE4D1",
  codeKeyword: "#6E7F00",
  codeString: "#1E857D",
  codeComment: "#586E75",
  codeNumber: "#C04114",
  codeFunction: "#1F74B3",
  codeType: "#9C6E00",
};

const DARK_SOLARIZED: SuperPIColorPalette = {
  dark: true,
  paper: "#00212B",
  surface: "#002B36",
  raised: "#073642",
  ink: "#E5ECEE",
  inkSecondary: "#B4C5C8",
  inkTertiary: "#839496",
  accent: "#2AA198",
  accentPressed: "#38B8AE",
  onAccent: "#001F26",
  accentSoft: "#0B3D44",
  userBubble: "#093946",
  userBubbleBorder: "#144C5C",
  danger: "#EC5E5B",
  onDanger: "#1A0808",
  success: "#95B023",
  warning: "#D4A319",
  hairline: "#12424F",
  wash: "#093744",
  composer: "#05313D",
  scrim: "rgba(0, 18, 24, 0.72)",
  codeBackground: "#001E27",
  inlineCodeBackground: "#073642",
  codeKeyword: "#859900",
  codeString: "#2AA198",
  codeComment: "#839496",
  codeNumber: "#DF6B3B",
  codeFunction: "#268BD2",
  codeType: "#B58900",
};

export const THEME_PRESETS: Record<ThemePresetId, ThemePresetDefinition> = {
  pi: {
    id: "pi",
    nameZh: "PI 黑白",
    nameEn: "PI Mono",
    light: LIGHT_PI,
    dark: DARK_PI,
  },
  "tokyo-night": {
    id: "tokyo-night",
    nameZh: "Tokyo Night",
    nameEn: "Tokyo Night",
    light: LIGHT_TOKYO_NIGHT,
    dark: DARK_TOKYO_NIGHT,
  },
  "night-owl": {
    id: "night-owl",
    nameZh: "Night Owl",
    nameEn: "Night Owl",
    light: LIGHT_NIGHT_OWL,
    dark: DARK_NIGHT_OWL,
  },
  catppuccin: {
    id: "catppuccin",
    nameZh: "Catppuccin",
    nameEn: "Catppuccin",
    light: LIGHT_CATPPUCCIN,
    dark: DARK_CATPPUCCIN,
  },
  nord: {
    id: "nord",
    nameZh: "Nord",
    nameEn: "Nord",
    light: LIGHT_NORD,
    dark: DARK_NORD,
  },
  "rose-pine": {
    id: "rose-pine",
    nameZh: "Rosé Pine",
    nameEn: "Rosé Pine",
    light: LIGHT_ROSE_PINE,
    dark: DARK_ROSE_PINE,
  },
  gruvbox: {
    id: "gruvbox",
    nameZh: "Gruvbox",
    nameEn: "Gruvbox",
    light: LIGHT_GRUVBOX,
    dark: DARK_GRUVBOX,
  },
  dracula: {
    id: "dracula",
    nameZh: "Dracula",
    nameEn: "Dracula",
    light: LIGHT_DRACULA,
    dark: DARK_DRACULA,
  },
  solarized: {
    id: "solarized",
    nameZh: "Solarized",
    nameEn: "Solarized",
    light: LIGHT_SOLARIZED,
    dark: DARK_SOLARIZED,
  },
};

const LEGACY_PRESET_MAP: Record<string, ThemePresetId> = {
  paper: "gruvbox",
  slate: "nord",
  "classic-blue": "tokyo-night",
  santorini: "solarized",
  oceanic: "night-owl",
};

export function normalizeThemePresetId(raw: string | null | undefined): ThemePresetId {
  if (!raw) return "pi";
  if (raw in THEME_PRESETS) return raw as ThemePresetId;
  return LEGACY_PRESET_MAP[raw] ?? "pi";
}

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
  const def = THEME_PRESETS[normalizeThemePresetId(preset)] ?? THEME_PRESETS.pi;
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
    accentSoft: `color-mix(in oklab, ${hex.trim()} ${dark ? 22 : 14}%, transparent)`,
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
  const resolvedAccent = accentOverride?.accent ?? palette.accent;
  const userBubble = accentOverride
    ? `color-mix(in oklab, ${resolvedAccent} ${dark ? 16 : 9}%, ${palette.surface})`
    : palette.userBubble;
  const userBubbleBorder = accentOverride
    ? `color-mix(in oklab, ${resolvedAccent} ${dark ? 28 : 20}%, ${palette.surface})`
    : palette.userBubbleBorder;
  const root = document.documentElement;
  root.classList.toggle("pi-dark", dark);
  root.style.colorScheme = dark ? "dark" : "light";

  const vars: Record<string, string> = {
    "--pi-chrome": palette.paper,
    "--pi-canvas": palette.surface,
    "--pi-canvas-border": dark
      ? "rgba(255, 255, 255, 0.085)"
      : `color-mix(in oklab, ${palette.ink} 8%, transparent)`,
    "--pi-canvas-shadow": dark
      ? "0 1px 0 rgba(255, 255, 255, 0.04) inset, 0 10px 28px -18px rgba(0, 0, 0, 0.6)"
      : "0 1px 2px rgba(15, 23, 42, 0.04), 0 8px 24px -16px rgba(15, 23, 42, 0.1)",
    "--pi-focus-ring": dark
      ? `color-mix(in oklab, ${resolvedAccent} 78%, #ffffff)`
      : `color-mix(in oklab, ${resolvedAccent} 82%, transparent)`,
    "--pi-scrollbar-thumb": dark ? "rgba(255, 255, 255, 0.22)" : "rgba(17, 17, 19, 0.22)",
    "--pi-skeleton": dark ? "rgba(255, 255, 255, 0.065)" : "rgba(17, 17, 19, 0.06)",
    "--pi-skeleton-shine": dark ? "rgba(255, 255, 255, 0.13)" : "rgba(17, 17, 19, 0.12)",
    "--pi-paper": palette.paper,
    "--pi-surface": palette.surface,
    "--pi-raised": palette.raised,
    "--pi-ink": palette.ink,
    "--pi-ink-secondary": palette.inkSecondary,
    "--pi-ink-tertiary": palette.inkTertiary,
    "--pi-accent": resolvedAccent,
    "--pi-accent-pressed": accentOverride?.accentPressed ?? palette.accentPressed,
    "--pi-on-accent": accentOverride?.onAccent ?? palette.onAccent,
    "--pi-accent-soft": accentOverride?.accentSoft ?? palette.accentSoft,
    "--pi-user-bubble": userBubble,
    "--pi-user-bubble-border": userBubbleBorder,
    "--pi-danger": palette.danger,
    "--pi-on-danger": palette.onDanger,
    "--pi-success": palette.success,
    "--pi-warning": palette.warning,
    "--pi-hairline": palette.hairline,
    "--pi-wash": palette.wash,
    "--pi-hover": dark ? "rgba(255,255,255,0.065)" : "rgba(0,0,0,0.04)",
    "--pi-composer": palette.composer,
    "--pi-scrim": palette.scrim,
    "--pi-surface-rim": dark ? "rgba(255,255,255,0.09)" : `${palette.hairline}B0`,
    "--pi-composer-shadow": dark
      ? "0 12px 32px -14px rgba(0, 0, 0, 0.62), 0 2px 6px rgba(0, 0, 0, 0.3)"
      : "0 10px 28px -12px rgba(11, 18, 32, 0.11), 0 2px 6px rgba(11, 18, 32, 0.04)",
    "--pi-popover-shadow": dark
      ? "0 20px 48px -14px rgba(0, 0, 0, 0.74), 0 2px 8px rgba(0, 0, 0, 0.42)"
      : "0 18px 42px -12px rgba(11, 18, 32, 0.16), 0 2px 8px rgba(11, 18, 32, 0.06)",
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
