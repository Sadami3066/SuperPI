import * as ContextMenuPrimitive from "@radix-ui/react-context-menu";
import { type ReactNode, useEffect, useRef } from "react";
import { useWorkbenchStore } from "../stores/workbenchStore";

export function ContextMenu({
  trigger,
  children,
}: {
  trigger: ReactNode;
  children: ReactNode;
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
    <ContextMenuPrimitive.Root onOpenChange={handleOpenChange}>
      <ContextMenuPrimitive.Trigger asChild>{trigger}</ContextMenuPrimitive.Trigger>
      <ContextMenuPrimitive.Portal>
        <ContextMenuPrimitive.Content
          collisionPadding={8}
          className="pi-popover pi-popover-anim z-50 min-w-[180px] p-1 outline-none"
        >
          {children}
        </ContextMenuPrimitive.Content>
      </ContextMenuPrimitive.Portal>
    </ContextMenuPrimitive.Root>
  );
}

export function ContextMenuItem({
  icon,
  label,
  shortcut,
  danger = false,
  disabled = false,
  onSelect,
}: {
  icon?: ReactNode;
  label: string;
  shortcut?: string;
  danger?: boolean;
  disabled?: boolean;
  onSelect: () => void;
}) {
  return (
    <ContextMenuPrimitive.Item
      disabled={disabled}
      onSelect={onSelect}
      className="flex cursor-pointer items-center gap-2 rounded-[8px] px-2.5 py-1.5 text-[13px] outline-none select-none data-[disabled]:cursor-default data-[disabled]:opacity-40 data-[highlighted]:bg-[var(--pi-hover)]"
      style={{ color: danger ? "var(--pi-danger)" : "var(--pi-ink)" }}
    >
      {icon && <span className="shrink-0 opacity-80">{icon}</span>}
      <span className="min-w-0 flex-1 truncate">{label}</span>
      {shortcut && (
        <span className="shrink-0 text-[11px] opacity-60" style={{ fontFamily: "var(--pi-font-mono)" }}>
          {shortcut}
        </span>
      )}
    </ContextMenuPrimitive.Item>
  );
}

export function ContextMenuSeparator() {
  return (
    <ContextMenuPrimitive.Separator
      className="my-1 h-[0.5px]"
      style={{ background: "var(--pi-hairline)" }}
    />
  );
}
