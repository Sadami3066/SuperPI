import * as DialogPrimitive from "@radix-ui/react-dialog";
import { type ReactNode, useEffect } from "react";
import { BrandIcon } from "../features/shared/BrandAssets";
import { useWorkbenchStore } from "../stores/workbenchStore";

export function Dialog({
  open,
  onOpenChange,
  title,
  description,
  maxWidthClass = "max-w-[480px]",
  children,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title?: string;
  description?: string;
  maxWidthClass?: string;
  children: ReactNode;
}) {
  useEffect(() => {
    if (!open) return undefined;
    useWorkbenchStore.getState().pushOverlay();
    return () => {
      useWorkbenchStore.getState().popOverlay();
    };
  }, [open]);

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay
          className="fixed inset-0 z-50"
          style={{ background: "var(--pi-scrim)" }}
        />
        <DialogPrimitive.Content
          className={`pi-popover fixed top-1/2 left-1/2 z-50 w-[calc(100vw-32px)] -translate-x-1/2 -translate-y-1/2 p-5 outline-none ${maxWidthClass}`}
        >
          {(title || description) && (
            <div className="mb-4 flex items-start justify-between gap-3">
              <div className="min-w-0">
                {title && (
                  <DialogPrimitive.Title
                    className="text-[16px] font-semibold"
                    style={{ color: "var(--pi-ink)" }}
                  >
                    {title}
                  </DialogPrimitive.Title>
                )}
                {description && (
                  <DialogPrimitive.Description
                    className="mt-1 text-[13px]"
                    style={{ color: "var(--pi-ink-tertiary)" }}
                  >
                    {description}
                  </DialogPrimitive.Description>
                )}
              </div>
              <DialogPrimitive.Close
                aria-label="Close"
                className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full transition-colors hover:bg-[var(--pi-hover)]"
                style={{ color: "var(--pi-ink-tertiary)" }}
              >
                <BrandIcon name="navigation/close" size={16} />
              </DialogPrimitive.Close>
            </div>
          )}
          {children}
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
