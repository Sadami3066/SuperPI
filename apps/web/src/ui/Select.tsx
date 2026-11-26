import * as SelectPrimitive from "@radix-ui/react-select";
import { BrandIcon } from "../features/shared/BrandAssets";

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export function Select({
  value,
  onValueChange,
  options,
  placeholder,
  ariaLabel,
  size = "md",
  className = "",
}: {
  value: string;
  onValueChange: (value: string) => void;
  options: SelectOption[];
  placeholder?: string;
  ariaLabel?: string;
  size?: "sm" | "md";
  className?: string;
}) {
  const triggerSize =
    size === "sm" ? "h-7 px-2.5 text-[12px] rounded-full" : "h-9 px-3 text-[13.5px] rounded-[10px]";

  return (
    <SelectPrimitive.Root value={value} onValueChange={onValueChange}>
      <SelectPrimitive.Trigger
        aria-label={ariaLabel}
        className={`inline-flex items-center justify-between gap-2 outline-none transition-colors hover:bg-[var(--pi-hover)] ${triggerSize} ${className}`}
        style={{
          background: size === "sm" ? "transparent" : "var(--pi-paper)",
          border: size === "sm" ? "0.5px solid transparent" : "0.5px solid var(--pi-hairline)",
          color: "var(--pi-ink)",
        }}
      >
        <SelectPrimitive.Value placeholder={placeholder} />
        <SelectPrimitive.Icon>
          <BrandIcon name="navigation/chevron_down" size={13} style={{ color: "var(--pi-ink-tertiary)" }} />
        </SelectPrimitive.Icon>
      </SelectPrimitive.Trigger>

      <SelectPrimitive.Portal>
        <SelectPrimitive.Content
          position="popper"
          sideOffset={6}
          className="pi-popover z-50 min-w-[var(--radix-select-trigger-width)] max-h-[260px] overflow-hidden p-1"
        >
          <SelectPrimitive.Viewport className="max-h-[250px] overflow-y-auto">
            {options.map((opt) => (
              <SelectPrimitive.Item
                key={opt.value}
                value={opt.value}
                disabled={opt.disabled}
                className="relative flex cursor-pointer items-center justify-between gap-2 rounded-[8px] px-2.5 py-1.5 text-[13px] outline-none select-none data-[disabled]:opacity-40 data-[highlighted]:bg-[var(--pi-hover)]"
                style={{ color: "var(--pi-ink)" }}
              >
                <SelectPrimitive.ItemText>{opt.label}</SelectPrimitive.ItemText>
                <SelectPrimitive.ItemIndicator>
                  <BrandIcon name="status/confirm" size={14} style={{ color: "var(--pi-accent)" }} />
                </SelectPrimitive.ItemIndicator>
              </SelectPrimitive.Item>
            ))}
          </SelectPrimitive.Viewport>
        </SelectPrimitive.Content>
      </SelectPrimitive.Portal>
    </SelectPrimitive.Root>
  );
}
