import * as TabsPrimitive from "@radix-ui/react-tabs";
import type { ReactNode } from "react";

export function Tabs({
  value,
  onValueChange,
  children,
  className = "",
}: {
  value: string;
  onValueChange: (val: string) => void;
  children: ReactNode;
  className?: string;
}) {
  return (
    <TabsPrimitive.Root value={value} onValueChange={onValueChange} className={className}>
      {children}
    </TabsPrimitive.Root>
  );
}

export function TabsList({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <TabsPrimitive.List
      className={`inline-flex rounded-[10px] p-0.5 ${className}`}
      style={{ background: "var(--pi-wash)" }}
    >
      {children}
    </TabsPrimitive.List>
  );
}

export function TabsTrigger({
  value,
  children,
  className = "",
}: {
  value: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <TabsPrimitive.Trigger
      value={value}
      className={`rounded-[8px] px-3 py-1.5 text-[12.5px] font-medium transition-colors outline-none data-[state=active]:bg-[var(--pi-surface)] data-[state=active]:text-[var(--pi-ink)] data-[state=inactive]:text-[var(--pi-ink-tertiary)] ${className}`}
    >
      {children}
    </TabsPrimitive.Trigger>
  );
}

export function TabsContent({
  value,
  children,
  className = "",
}: {
  value: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <TabsPrimitive.Content value={value} className={`outline-none ${className}`}>
      {children}
    </TabsPrimitive.Content>
  );
}
