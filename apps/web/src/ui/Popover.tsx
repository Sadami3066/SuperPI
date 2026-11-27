import * as PopoverPrimitive from "@radix-ui/react-popover";
import { type ReactNode, useEffect, useRef } from "react";
import { useWorkbenchStore } from "../stores/workbenchStore";

export function Popover({
  open,
  onOpenChange,
  trigger,
  children,
  align = "start",
  side = "bottom",
  sideOffset = 8,
  className = "",
}: {
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  trigger: ReactNode;
  children: ReactNode;
  align?: "start" | "center" | "end";
  side?: "top" | "bottom" | "left" | "right";
  sideOffset?: number;
  className?: string;
}) {
  const pushedRef = useRef(false);

  const syncOverlay = (nextOpen: boolean) => {
    if (nextOpen && !pushedRef.current) {
      pushedRef.current = true;
      useWorkbenchStore.getState().pushOverlay();
    } else if (!nextOpen && pushedRef.current) {
      pushedRef.current = false;
      useWorkbenchStore.getState().popOverlay();
    }
  };

  useEffect(() => {
    if (typeof open === "boolean") {
      syncOverlay(open);
    }
  }, [open]);

  useEffect(() => {
    return () => {
      if (pushedRef.current) {
        pushedRef.current = false;
        useWorkbenchStore.getState().popOverlay();
      }
    };
  }, []);

  return (
    <PopoverPrimitive.Root
      open={open}
      onOpenChange={(nextOpen) => {
        syncOverlay(nextOpen);
        onOpenChange?.(nextOpen);
      }}
    >
      <PopoverPrimitive.Trigger asChild>{trigger}</PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          align={align}
          side={side}
          sideOffset={sideOffset}
          collisionPadding={12}
          className={`pi-popover pi-popover-anim z-50 outline-none ${className}`}
        >
          {children}
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  );
}
