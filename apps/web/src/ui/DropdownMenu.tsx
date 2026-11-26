import * as DropdownMenuPrimitive from "@radix-ui/react-dropdown-menu";
import { type ReactNode, useEffect, useRef } from "react";
import { useWorkbenchStore } from "../stores/workbenchStore";

export function DropdownMenu({
  trigger,
  children,
  align = "end",
  sideOffset = 6,
}: {
  trigger: ReactNode;
  children: ReactNode;
  align?: "start" | "center" | "end";
  sideOffset?: number;
}) {
  const pushedRef = useRef(false);

  const handleOpenChange = (open: boolean) => {
    if (open && !pushedRef.current) {
      pushedRef.current = true;
      useWorkbenchStore.getState().pushOverlay();
    } else if (!open && pushedRef.current) {
      pushedRef.current = false;
      useWorkbenchStore.getState().popOverlay();
    }
  };

  useEffect(() => {
    return () => {
      if (pushedRef.current) {
        pushedRef.current = false;
        useWorkbenchStore.getState().popOverlay();
      }
    };
  }, []);

  return (
    <DropdownMenuPrimitive.Root onOpenChange={handleOpenChange}>
      <DropdownMenuPrimitive.Trigger asChild>{trigger}</DropdownMenuPrimitive.Trigger>
      <DropdownMenuPrimitive.Portal>
        <DropdownMenuPrimitive.Content
          align={align}
          sideOffset={sideOffset}
          className="pi-popover z-50 min-w-[172px] p-1 outline-none"
        >
          {children}
        </DropdownMenuPrimitive.Content>
      </DropdownMenuPrimitive.Portal>
    </DropdownMenuPrimitive.Root>
  );
}

export function DropdownItem({
  icon,
  label,
  danger = false,
  onSelect,
}: {
  icon?: ReactNode;
  label: string;
  danger?: boolean;
  onSelect: () => void;
}) {
  return (
    <DropdownMenuPrimitive.Item
      onSelect={onSelect}
      className="flex cursor-pointer items-center gap-2 rounded-[8px] px-2.5 py-1.5 text-[13px] outline-none select-none data-[highlighted]:bg-[var(--pi-hover)]"
      style={{ color: danger ? "var(--pi-danger)" : "var(--pi-ink)" }}
    >
      {icon && <span className="shrink-0 opacity-80">{icon}</span>}
      <span className="truncate">{label}</span>
    </DropdownMenuPrimitive.Item>
  );
}

export function DropdownSeparator() {
  return (
    <DropdownMenuPrimitive.Separator
      className="my-1 h-[0.5px]"
      style={{ background: "var(--pi-hairline)" }}
    />
  );
}
