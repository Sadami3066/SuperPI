import { useState } from "react";
import { IconDeviceMobile } from "@tabler/icons-react";

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

export function BotAvatar({
  avatar,
  name,
  isHome,
  isRemote,
  dark,
  size = 24,
  rounded = "rounded-[7px]",
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
        style={{ width: size, height: size }}
      />
    );
  }

  if (isHome && !isRemote) {
    return <PiBrandMark size={size} dark={dark} />;
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
      }}
    >
      {isRemote && !name.trim() ? <IconDeviceMobile size={Math.round(size * 0.56)} /> : initial}
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
