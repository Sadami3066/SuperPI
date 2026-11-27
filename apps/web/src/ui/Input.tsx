import { forwardRef, type InputHTMLAttributes, type TextareaHTMLAttributes } from "react";

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className = "", style, ...rest }, ref) {
    return (
      <input
        ref={ref}
        className={`pi-input h-9 w-full rounded-[10px] px-3 text-[13.5px] transition-[background-color,border-color,box-shadow] duration-150 placeholder:opacity-55 disabled:opacity-45 ${className}`}
        style={{
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
      className={`pi-input w-full rounded-[12px] px-3 py-2 text-[13.5px] leading-[1.55] transition-[background-color,border-color,box-shadow] duration-150 placeholder:opacity-55 disabled:opacity-45 ${className}`}
      style={{
        color: "var(--pi-ink)",
        ...style,
      }}
      {...rest}
    />
  );
});
