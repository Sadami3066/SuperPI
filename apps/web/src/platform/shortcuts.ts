export function isShortcutBlockedByTarget(
  target: EventTarget | null,
  options: { allowWhenModPressed?: boolean; hasMod?: boolean } = {},
): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target.closest("[data-shortcut-ignore='true']") || target.closest(".xterm")) return true;

  const tag = target.tagName;
  const isFormField =
    tag === "INPUT" ||
    tag === "TEXTAREA" ||
    tag === "SELECT" ||
    target.isContentEditable;

  if (!isFormField) return false;
  if (options.allowWhenModPressed && options.hasMod) return false;
  return true;
}

export function isMacPlatform(platform?: string): boolean {
  const source =
    platform ??
    (typeof navigator !== "undefined" ? navigator.platform : "");
  return /mac|darwin/i.test(source);
}

export function formatShortcut(keys: string[], platform?: string): string {
  const mac = isMacPlatform(platform);
  return keys
    .map((part) => {
      const lower = part.trim().toLowerCase();
      if (lower === "mod" || lower === "cmdorctrl") return mac ? "⌘" : "Ctrl";
      if (lower === "ctrl") return mac ? "⌃" : "Ctrl";
      if (lower === "alt" || lower === "option") return mac ? "⌥" : "Alt";
      if (lower === "shift") return mac ? "⇧" : "Shift";
      if (lower === "enter") return mac ? "↩" : "Enter";
      if (lower === "tab") return "Tab";
      if (lower === "esc" || lower === "escape") return "Esc";
      return part.toUpperCase();
    })
    .join(mac ? "" : "+");
}
