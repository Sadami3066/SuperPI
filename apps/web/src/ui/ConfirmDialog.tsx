import * as AlertDialogPrimitive from "@radix-ui/react-alert-dialog";
import { useEffect } from "react";
import { useWorkbenchStore } from "../stores/workbenchStore";
import { Button } from "./Button";

export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  cancelLabel,
  danger = true,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmLabel: string;
  cancelLabel: string;
  danger?: boolean;
  onConfirm: () => void;
}) {
  useEffect(() => {
    if (!open) return undefined;
    useWorkbenchStore.getState().pushOverlay();
    return () => {
      useWorkbenchStore.getState().popOverlay();
    };
  }, [open]);

  return (
    <AlertDialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <AlertDialogPrimitive.Portal>
        <AlertDialogPrimitive.Overlay
          className="fixed inset-0 z-50"
          style={{ background: "var(--pi-scrim)" }}
        />
        <AlertDialogPrimitive.Content className="pi-popover fixed top-1/2 left-1/2 z-50 w-[calc(100vw-32px)] max-w-[400px] -translate-x-1/2 -translate-y-1/2 p-5 outline-none">
          <AlertDialogPrimitive.Title
            className="text-[15.5px] font-semibold"
            style={{ color: "var(--pi-ink)" }}
          >
            {title}
          </AlertDialogPrimitive.Title>
          <AlertDialogPrimitive.Description
            className="mt-2 text-[13.5px] leading-[1.55]"
            style={{ color: "var(--pi-ink-secondary)" }}
          >
            {description}
          </AlertDialogPrimitive.Description>
          <div className="mt-5 flex justify-end gap-2">
            <AlertDialogPrimitive.Cancel asChild>
              <Button variant="secondary" size="sm">
                {cancelLabel}
              </Button>
            </AlertDialogPrimitive.Cancel>
            <AlertDialogPrimitive.Action asChild>
              <Button
                variant={danger ? "danger" : "primary"}
                size="sm"
                onClick={() => {
                  onConfirm();
                  onOpenChange(false);
                }}
              >
                {confirmLabel}
              </Button>
            </AlertDialogPrimitive.Action>
          </div>
        </AlertDialogPrimitive.Content>
      </AlertDialogPrimitive.Portal>
    </AlertDialogPrimitive.Root>
  );
}
