import { type CSSProperties, useEffect, useState } from "react";
import {
  IconBrandApple,
  IconBrandWindows,
  IconDeviceDesktop,
  IconDeviceMobile,
} from "@tabler/icons-react";

export type DeviceOsKind = "macos" | "windows" | "linux" | "android" | "ios" | "desktop";

export function resolveDeviceOsKind(
  platform?: string | null,
  capabilities?: string[] | null,
): DeviceOsKind {
  const capOs = (capabilities ?? [])
    .find((c) => c.toLowerCase().startsWith("os:"))
    ?.slice(3)
    .trim()
    .toLowerCase();
  const raw = (capOs || platform || "").trim().toLowerCase();
  if (raw.includes("mac") || raw.includes("darwin") || raw.includes("apple")) return "macos";
  if (raw.includes("win")) return "windows";
  if (raw.includes("android")) return "android";
  if (raw.includes("ios") || raw.includes("iphone") || raw.includes("ipad")) return "ios";
  if (raw.includes("linux")) return "linux";
  return "desktop";
}

export function DevicePlatformIcon({
  platform,
  capabilities,
  size = 13.5,
  className = "",
  style,
}: {
  platform?: string | null;
  capabilities?: string[] | null;
  size?: number;
  className?: string;
  style?: CSSProperties;
}) {
  const kind = resolveDeviceOsKind(platform, capabilities);
  if (kind === "macos") {
    return <IconBrandApple size={size} stroke={1.75} className={`shrink-0 ${className}`} style={style} />;
  }
  if (kind === "windows") {
    return <IconBrandWindows size={size} stroke={1.75} className={`shrink-0 ${className}`} style={style} />;
  }
  if (kind === "android" || kind === "ios") {
    return <IconDeviceMobile size={size} stroke={1.75} className={`shrink-0 ${className}`} style={style} />;
  }
  return <IconDeviceDesktop size={size} stroke={1.75} className={`shrink-0 ${className}`} style={style} />;
}

const SVG_ICONS = new Set([
  "action/eye",
  "action/pen",
  "action/quote",
  "action/select_all",
  "navigation/arrow_up",
  "navigation/chevron_down",
  "navigation/chevron_right",
  "system/calendar",
  "system/desktop",
  "system/theme",
]);

export type MascotMood = "wave" | "thinking" | "waiting" | "done" | "alert" | "confused";

export function PiBrandMark({
  size = 28,
  dark,
  className = "",
}: {
  size?: number;
  dark: boolean;
  className?: string;
}) {
  const src = dark ? "/brand/pi_brand_bot_dark.webp" : "/brand/pi_brand_bot_light.webp";
  return (
    <img
      src={src}
      alt="Super PI"
      width={size}
      height={size}
      draggable={false}
      className={`shrink-0 rounded-[8px] object-contain select-none ${className}`}
      style={{ width: size, height: size }}
    />
  );
}

export function PiActivityIcon({
  size = 16,
  dark,
  className = "",
}: {
  size?: number;
  dark: boolean;
  className?: string;
}) {
  return (
    <img
      src="/brand/process_activity.webp"
      alt=""
      aria-hidden="true"
      width={size}
      height={size}
      draggable={false}
      className={`shrink-0 object-contain select-none ${className}`}
      style={{
        width: size,
        height: size,
        filter: dark ? "invert(0.92) hue-rotate(180deg)" : undefined,
      }}
    />
  );
}

export function PiMascot({
  mood,
  size = 56,
  dark,
  className = "",
}: {
  mood: MascotMood;
  size?: number;
  dark: boolean;
  className?: string;
}) {
  const src = `/brand/v2_${mood}.webp`;
  return (
    <img
      src={src}
      alt="Super PI"
      width={size}
      height={size}
      draggable={false}
      className={`shrink-0 object-contain select-none ${className}`}
      style={{
        width: size,
        height: size,
        filter: dark ? "invert(0.92) hue-rotate(180deg)" : undefined,
      }}
    />
  );
}

export function BrandIcon({
  name,
  size = 16,
  className = "",
  style,
}: {
  name: string;
  size?: number;
  className?: string;
  style?: CSSProperties;
}) {
  const ext = SVG_ICONS.has(name) ? "svg" : "png";
  const maskUrl = `url("/icons/${name}.${ext}")`;
  return (
    <span
      aria-hidden="true"
      className={`inline-block shrink-0 select-none ${className}`}
      style={{
        width: size,
        height: size,
        backgroundColor: "currentColor",
        maskImage: maskUrl,
        WebkitMaskImage: maskUrl,
        maskSize: "contain",
        WebkitMaskSize: "contain",
        maskRepeat: "no-repeat",
        WebkitMaskRepeat: "no-repeat",
        maskPosition: "center",
        WebkitMaskPosition: "center",
        ...style,
      }}
    />
  );
}

export function BotAvatar({
  avatar,
  name,
  isHome,
  isRemote,
  dark,
  size = 24,
  rounded = "rounded-full",
}: {
  avatar?: string | null;
  name: string;
  isHome?: boolean;
  isRemote?: boolean;
  dark: boolean;
  size?: number;
  rounded?: string;
}) {
  const [broken, setBroken] = useState(false);

  useEffect(() => {
    setBroken(false);
  }, [avatar]);

  if (avatar && !broken) {
    return (
      <img
        src={avatar}
        alt={name}
        width={size}
        height={size}
        draggable={false}
        onError={() => setBroken(true)}
        className={`shrink-0 object-cover ${rounded}`}
        style={{ width: size, height: size, border: "0.5px solid var(--pi-hairline)" }}
      />
    );
  }

  if (isHome || !avatar) {
    return (
      <span
        className="inline-flex shrink-0 items-center justify-center overflow-hidden rounded-full select-none"
        style={{
          width: size,
          height: size,
          background: "var(--pi-paper)",
          border: "0.5px solid var(--pi-hairline)",
        }}
        aria-label={name}
      >
        <PiBrandMark size={Math.max(14, Math.round(size * 0.78))} dark={dark} className="rounded-full" />
      </span>
    );
  }

  const initial = (name.trim()[0] || "B").toUpperCase();
  return (
    <span
      className={`inline-flex shrink-0 items-center justify-center font-semibold select-none ${rounded}`}
      style={{
        width: size,
        height: size,
        fontSize: Math.max(10, Math.round(size * 0.46)),
        background: "var(--pi-accent-soft)",
        color: "var(--pi-accent)",
        border: isRemote ? "0.5px solid var(--pi-hairline)" : undefined,
      }}
    >
      {initial}
    </span>
  );
}

export function ProviderIcon({
  providerId,
  name,
  dark,
  size = 20,
}: {
  providerId: string;
  name: string;
  dark: boolean;
  size?: number;
}) {
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    setFailed(false);
  }, [providerId, dark]);

  const slug = providerId.toLowerCase().replace(/[^a-z0-9_]/g, "_");
  const folder = dark ? "dark" : "light";
  const src = `/providers/${folder}/provider_${slug}.png`;

  if (failed) {
    return (
      <span
        className="inline-flex shrink-0 items-center justify-center rounded-[6px] text-[11px] font-semibold select-none"
        style={{
          width: size,
          height: size,
          background: "var(--pi-wash)",
          color: "var(--pi-ink-secondary)",
        }}
      >
        {(name.trim()[0] || "?").toUpperCase()}
      </span>
    );
  }

  return (
    <img
      src={src}
      alt={name}
      width={size}
      height={size}
      draggable={false}
      onError={() => setFailed(true)}
      className="shrink-0 rounded-[5px] object-contain"
      style={{ width: size, height: size }}
    />
  );
}
