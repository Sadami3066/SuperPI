import { type ButtonHTMLAttributes, forwardRef, type ReactNode } from "react";

export const IconButton = forwardRef<
  HTMLButtonElement,
  ButtonHTMLAttributes<HTMLButtonElement> & {
    label: string;
    active?: boolean;
    size?: "sm" | "md";
    children: ReactNode;
  }
>(function IconButton(
  { label, active = false, size = "md", className = "", children, style, ...rest },
  ref,
) {
  const dim = size === "sm" ? "h-7 w-7" : "h-8 w-8";
  return (
    <button
      ref={ref}
      type={rest.type ?? "button"}
      title={rest.title ?? label}
      aria-label={rest["aria-label"] ?? label}
      className={`inline-flex shrink-0 items-center justify-center rounded-[9px] transition-[background-color,color,transform] duration-150 hover:bg-[var(--pi-hover)] hover:text-[var(--pi-ink)] motion-safe:active:scale-[0.95] disabled:pointer-events-none disabled:opacity-35 ${dim} ${className}`}
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
});
