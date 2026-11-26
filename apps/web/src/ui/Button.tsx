import type { ButtonHTMLAttributes, ReactNode } from "react";

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
export type ButtonSize = "sm" | "md";

export function Button({
  variant = "secondary",
  size = "md",
  className = "",
  children,
  style,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
  children: ReactNode;
}) {
  const heightCls = size === "sm" ? "h-8 px-3 text-[12.5px]" : "h-9 px-4 text-[13.5px]";

  const variantStyle =
    variant === "primary"
      ? {
          background: "var(--pi-accent)",
          color: "var(--pi-on-accent)",
        }
      : variant === "danger"
        ? {
            background: "var(--pi-danger)",
            color: "var(--pi-on-danger)",
          }
        : variant === "ghost"
          ? {
              background: "transparent",
              color: "var(--pi-ink-secondary)",
            }
          : {
              background: "var(--pi-surface)",
              color: "var(--pi-ink)",
              border: "0.5px solid var(--pi-hairline)",
            };

  return (
    <button
      type={rest.type ?? "button"}
      className={`inline-flex shrink-0 items-center justify-center gap-1.5 rounded-[10px] font-medium transition-opacity hover:opacity-90 disabled:pointer-events-none disabled:opacity-40 ${heightCls} ${className}`}
      style={{ ...variantStyle, ...style }}
      {...rest}
    >
      {children}
    </button>
  );
}
