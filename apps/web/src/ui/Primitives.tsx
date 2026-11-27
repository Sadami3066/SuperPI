import type { ReactNode } from "react";

export function Card({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}) {
  return <div className={`pi-card ${className}`}>{children}</div>;
}

export function Badge({
  children,
  tone = "neutral",
  className = "",
}: {
  children: ReactNode;
  tone?: "neutral" | "accent" | "success" | "warning" | "danger";
  className?: string;
}) {
  const palette =
    tone === "accent"
      ? { background: "var(--pi-accent-soft)", color: "var(--pi-accent)" }
      : tone === "success"
        ? { background: "color-mix(in oklab, var(--pi-success) 14%, transparent)", color: "var(--pi-success)" }
        : tone === "warning"
          ? { background: "color-mix(in oklab, var(--pi-warning) 14%, transparent)", color: "var(--pi-warning)" }
          : tone === "danger"
            ? { background: "color-mix(in oklab, var(--pi-danger) 14%, transparent)", color: "var(--pi-danger)" }
            : { background: "var(--pi-wash)", color: "var(--pi-ink-secondary)" };

  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ${className}`}
      style={palette}
    >
      {children}
    </span>
  );
}

export function NoticeBar({
  tone = "info",
  message,
  action,
  className = "",
}: {
  tone?: "info" | "danger" | "warning";
  message: string;
  action?: ReactNode;
  className?: string;
}) {
  const color =
    tone === "danger"
      ? "var(--pi-danger)"
      : tone === "warning"
        ? "var(--pi-warning)"
        : "var(--pi-ink-secondary)";

  return (
    <div
      className={`flex items-center justify-between gap-3 rounded-[12px] px-3.5 py-2 text-[12.5px] ${className}`}
      style={{
        background: "var(--pi-wash)",
        border: "0.5px solid var(--pi-hairline)",
        color,
      }}
    >
      <span className="min-w-0 truncate">{message}</span>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  );
}

export function EmptyState({
  icon,
  title,
  description,
  action,
}: {
  icon?: ReactNode;
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-10 text-center">
      {icon && (
        <div
          className="mb-3 flex h-11 w-11 items-center justify-center rounded-full"
          style={{ background: "var(--pi-wash)", color: "var(--pi-ink-secondary)" }}
        >
          {icon}
        </div>
      )}
      <div className="text-[15px] font-semibold" style={{ color: "var(--pi-ink)" }}>
        {title}
      </div>
      {description && (
        <p
          className="mt-1.5 max-w-[360px] text-[13px] leading-[1.55]"
          style={{ color: "var(--pi-ink-tertiary)" }}
        >
          {description}
        </p>
      )}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function Skeleton({ className = "" }: { className?: string }) {
  return <div aria-hidden="true" className={`pi-skeleton-pulse rounded-[8px] ${className}`} />;
}

export function ChatLoadingSkeleton() {
  return (
    <div
      aria-busy="true"
      className="mx-auto w-full max-w-[760px] space-y-6 px-4 py-6 sm:px-6"
    >
      <div className="flex justify-end">
        <Skeleton className="h-10 w-[52%] max-w-[320px] rounded-[18px]" />
      </div>
      <div className="space-y-2.5">
        <Skeleton className="h-4 w-32 rounded-full" />
        <Skeleton className="h-4 w-[92%]" />
        <Skeleton className="h-4 w-[78%]" />
        <Skeleton className="h-4 w-[62%]" />
      </div>
      <div className="flex justify-end pt-2">
        <Skeleton className="h-9 w-[38%] max-w-[240px] rounded-[18px]" />
      </div>
      <div className="space-y-2.5">
        <Skeleton className="h-4 w-28 rounded-full" />
        <Skeleton className="h-4 w-[86%]" />
        <Skeleton className="h-4 w-[68%]" />
      </div>
    </div>
  );
}
