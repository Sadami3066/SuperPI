import * as SwitchPrimitive from "@radix-ui/react-switch";

export function Switch({
  checked,
  onCheckedChange,
  disabled = false,
  ariaLabel,
}: {
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  disabled?: boolean;
  ariaLabel?: string;
}) {
  return (
    <SwitchPrimitive.Root
      checked={checked}
      onCheckedChange={onCheckedChange}
      disabled={disabled}
      aria-label={ariaLabel}
      className="relative inline-flex h-[22px] w-[38px] shrink-0 cursor-pointer items-center rounded-full p-0.5 transition-colors disabled:cursor-not-allowed disabled:opacity-45"
      style={{
        background: checked ? "var(--pi-accent)" : "var(--pi-wash)",
        border: "0.5px solid var(--pi-hairline)",
      }}
    >
      <SwitchPrimitive.Thumb
        className="block h-4 w-4 rounded-full shadow-xs transition-transform duration-150"
        style={{
          background: checked ? "var(--pi-on-accent)" : "var(--pi-surface)",
          transform: checked ? "translateX(16px)" : "translateX(0px)",
        }}
      />
    </SwitchPrimitive.Root>
  );
}
