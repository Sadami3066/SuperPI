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

  const variantCls =
    variant === "primary"
      ? "hover:opacity-90"
      : variant === "danger"
        ? "hover:opacity-90"
        : variant === "ghost"
          ? "hover:bg-[var(--pi-hover)] hover:text-[var(--pi-ink)]"
          : "hover:bg-[var(--pi-hover)]";

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
              background: "var(--pi-canvas)",
              color: "var(--pi-ink)",
              border: "1px solid var(--pi-canvas-border)",
            };

  return (
    <button
      type={rest.type ?? "button"}
      className={`inline-flex shrink-0 items-center justify-center gap-1.5 rounded-[10px] font-medium transition-[background-color,border-color,color,opacity,transform] duration-150 motion-safe:active:scale-[0.98] disabled:pointer-events-none disabled:opacity-40 ${heightCls} ${variantCls} ${className}`}
      style={{ ...variantStyle, ...style }}
      {...rest}
    >
      {children}
    </button>
  );
}
