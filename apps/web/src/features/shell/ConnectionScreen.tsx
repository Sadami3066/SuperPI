import { useState } from "react";
import { IconKey } from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import type { ConnectionPhase } from "../../stores/connectionStore";
import { Button, Card, Input } from "../../ui";
import { BrandIcon, PiBrandMark, PiMascot } from "../shared/BrandAssets";
import { WindowControls } from "./WindowControls";

export function ConnectionScreen({
  phase,
  dark,
  isElectron = false,
  error,
  onRetry,
  onSubmitToken,
}: {
  phase: Exclude<ConnectionPhase, "live">;
  dark: boolean;
  isElectron?: boolean;
  error: string | null;
  onRetry: () => void;
  onSubmitToken: (token: string) => void;
}) {
  const { t } = useI18n();
  const [tokenInput, setTokenInput] = useState("");

  return (
    <div
      className="flex h-screen w-screen flex-col"
      style={{ background: "var(--pi-paper)", color: "var(--pi-ink)" }}
    >
      <div className="pi-drag-region flex h-[40px] shrink-0 items-center justify-end">
        <WindowControls />
      </div>

      <div className="flex min-h-0 flex-1 items-center justify-center p-6">
        <Card className="w-full max-w-[400px] p-6 text-center">
          <div className="mb-4 flex justify-center">
            {phase === "connecting" ? (
              <PiMascot mood="thinking" size={56} dark={dark} />
            ) : phase === "error" ? (
              <PiMascot mood="alert" size={56} dark={dark} />
            ) : (
              <PiBrandMark size={52} dark={dark} />
            )}
          </div>

          {phase === "connecting" && (
            <div className="space-y-2.5">
              <div className="text-[15.5px] font-semibold">{t("app.name")}</div>
              <div
                className="flex items-center justify-center gap-2 text-[13px]"
                style={{ color: "var(--pi-ink-tertiary)" }}
              >
                <span
                  className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-[1.75px] border-current border-t-transparent"
                  style={{ color: "var(--pi-accent)" }}
                />
                <span>{isElectron ? t("conn.electronStarting") : t("conn.connecting")}</span>
              </div>
            </div>
          )}

          {phase === "error" && (
            <div className="space-y-4">
              <div>
                <div className="text-[15.5px] font-semibold">
                  {isElectron ? t("conn.electronFailedTitle") : t("conn.failedTitle")}
                </div>
                <p
                  className="mt-1.5 text-[13px] leading-[1.55]"
                  style={{ color: "var(--pi-ink-tertiary)" }}
                >
                  {isElectron ? t("conn.electronFailedHint") : error || t("conn.failedHint")}
                </p>
              </div>
              <Button variant="primary" onClick={onRetry} className="w-full">
                <BrandIcon name="action/retry" size={15} />
                <span>{t("conn.retry")}</span>
              </Button>
            </div>
          )}

          {phase === "needs_token" && !isElectron && (
            <form
              className="space-y-3.5 text-left"
              onSubmit={(e) => {
                e.preventDefault();
                if (tokenInput.trim()) onSubmitToken(tokenInput.trim());
              }}
            >
              <div className="text-center">
                <div className="text-[15.5px] font-semibold">{t("conn.tokenTitle")}</div>
                <p className="mt-1 text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {error ? t("conn.invalidToken") : t("conn.tokenHint")}
                </p>
              </div>
              <div className="relative">
                <IconKey
                  size={15}
                  stroke={1.7}
                  className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2"
                  style={{ color: "var(--pi-ink-tertiary)" }}
                />
                <Input
                  type="password"
                  value={tokenInput}
                  onChange={(e) => setTokenInput(e.target.value)}
                  placeholder={t("conn.tokenPlaceholder")}
                  className="pl-9"
                />
              </div>
              <Button type="submit" variant="primary" disabled={!tokenInput.trim()} className="w-full">
                {t("conn.tokenSubmit")}
              </Button>
            </form>
          )}
        </Card>
      </div>
    </div>
  );
}
