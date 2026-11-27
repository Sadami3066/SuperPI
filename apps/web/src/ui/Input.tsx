import { forwardRef, type InputHTMLAttributes, type TextareaHTMLAttributes } from "react";

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className = "", style, ...rest }, ref) {
    return (
      <input
        ref={ref}
        className={`h-9 w-full rounded-[10px] px-3 text-[13.5px] transition-[background-color,border-color,box-shadow] duration-150 placeholder:opacity-55 focus:border-[var(--pi-focus-ring)] disabled:opacity-45 ${className}`}
        style={{
          background: "var(--pi-chrome)",
          border: "1px solid var(--pi-canvas-border)",
          color: "var(--pi-ink)",
          ...style,
        }}
        {...rest}
      />
    );
  },
);

export const Textarea = forwardRef<
  HTMLTextAreaElement,
  TextareaHTMLAttributes<HTMLTextAreaElement>
>(function Textarea({ className = "", style, ...rest }, ref) {
  return (
    <textarea
      ref={ref}
      className={`w-full rounded-[12px] px-3 py-2 text-[13.5px] leading-[1.55] transition-[background-color,border-color,box-shadow] duration-150 placeholder:opacity-55 focus:border-[var(--pi-focus-ring)] disabled:opacity-45 ${className}`}
      style={{
        background: "var(--pi-chrome)",
        border: "1px solid var(--pi-canvas-border)",
        color: "var(--pi-ink)",
        ...style,
      }}
      {...rest}
    />
  );
});
