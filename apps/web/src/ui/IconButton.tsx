import type { ButtonHTMLAttributes, ReactNode } from "react";

export function IconButton({
  label,
  active = false,
  size = "md",
  className = "",
  children,
  style,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  label: string;
  active?: boolean;
  size?: "sm" | "md";
  children: ReactNode;
}) {
  const dim = size === "sm" ? "h-7 w-7" : "h-8 w-8";
  return (
    <button
      type={rest.type ?? "button"}
      title={label}
      aria-label={label}
      className={`inline-flex shrink-0 items-center justify-center rounded-full transition-colors hover:bg-[var(--pi-hover)] disabled:pointer-events-none disabled:opacity-35 ${dim} ${className}`}
      style={{
        background: active ? "var(--pi-wash)" : "transparent",
        color: active ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
        ...style,
      }}
      {...rest}
    >
      {children}
    </button>
  );
}
