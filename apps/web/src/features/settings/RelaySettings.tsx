import { useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { formatPlatformName } from "../../api/formatters";
import type { HostRelayDeviceDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { Badge, Button, Card, ConfirmDialog, Dialog, Input, Switch } from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";

export function RelaySettings() {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const relayStatus = useSettingsStore((s) => s.relayStatus);
  const relayDevices = useSettingsStore((s) => s.relayDevices);
  const generatePairCode = useSettingsStore((s) => s.generatePairCode);
  const pairRelay = useSettingsStore((s) => s.pairRelay);
  const logoutRelay = useSettingsStore((s) => s.logoutRelay);
  const renameRelayDevice = useSettingsStore((s) => s.renameRelayDevice);
  const revokeRelayDevice = useSettingsStore((s) => s.revokeRelayDevice);
  const setRelayAllowRemote = useSettingsStore((s) => s.setRelayAllowRemote);

  const [pairLink, setPairLink] = useState("");
  const [qrLink, setQrLink] = useState<string | null>(null);
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [renameTarget, setRenameTarget] = useState<HostRelayDeviceDto | null>(null);
  const [renameDraft, setRenameDraft] = useState("");
  const [revokeTarget, setRevokeTarget] = useState<HostRelayDeviceDto | null>(null);
  const copyTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (copyTimerRef.current !== null) clearTimeout(copyTimerRef.current);
    };
  }, []);

  useEffect(() => {
    if (!qrLink) {
      setQrDataUrl(null);
      return;
    }
    let cancelled = false;
    void QRCode.toDataURL(qrLink, { width: 220, margin: 1 }).then((url) => {
      if (!cancelled) setQrDataUrl(url);
    });
    return () => {
      cancelled = true;
    };
  }, [qrLink]);

  const enrolled = Boolean(relayStatus?.enrolled);

  return (
    <div className="space-y-5">
      <Card className="space-y-4 p-5">
        <div className="flex items-center justify-between gap-3">
          <div>
            <div className="text-[14.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
              {t("relay.title")}
            </div>
            {relayStatus?.origin && (
              <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {relayStatus.origin}
              </div>
            )}
          </div>
          <Badge tone={enrolled ? "success" : "neutral"}>
            {enrolled ? t("relay.statusConnected") : t("relay.statusDisconnected")}
          </Badge>
        </div>

        <div className="flex items-center justify-between gap-3">
          <span className="text-[13.5px]" style={{ color: "var(--pi-ink)" }}>
            {t("relay.allowRemote")}
          </span>
          <Switch
            checked={Boolean(relayStatus?.allowRemote)}
            ariaLabel={t("relay.allowRemote")}
            onCheckedChange={(checked) => {
              if (client) void setRelayAllowRemote(client, checked);
            }}
          />
        </div>

        {enrolled ? (
          <div className="space-y-3 pt-1">
            <div className="flex flex-wrap gap-2">
              <Button
                variant="primary"
                size="sm"
                disabled={busy}
                onClick={() => {
                  if (!client) return;
                  setBusy(true);
                  setError(null);
                  void generatePairCode(client)
                    .then((res) => setQrLink(res.link))
                    .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                    .finally(() => setBusy(false));
                }}
              >
                <BrandIcon name="action/scan" size={15} />
                <span>{t("relay.generateQr")}</span>
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={busy}
                onClick={() => {
                  if (!client) return;
                  setBusy(true);
                  void logoutRelay(client).finally(() => setBusy(false));
                }}
              >
                {t("relay.logout")}
              </Button>
            </div>

            {qrLink && (
              <div
                className="flex flex-col items-center gap-3 rounded-[14px] p-4 text-center"
                style={{ background: "var(--pi-paper)", border: "0.5px solid var(--pi-hairline)" }}
              >
                {qrDataUrl && (
                  <img
                    src={qrDataUrl}
                    alt="Relay QR"
                    width={196}
                    height={196}
                    className="rounded-[10px] bg-white p-2"
                  />
                )}
                <p className="text-[12.5px]" style={{ color: "var(--pi-ink-secondary)" }}>
                  {t("relay.scanHint")}
                </p>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => {
                    void navigator.clipboard?.writeText(qrLink);
                    setCopied(true);
                    if (copyTimerRef.current !== null) clearTimeout(copyTimerRef.current);
                    copyTimerRef.current = setTimeout(() => setCopied(false), 1200);
                  }}
                >
                  {copied ? <BrandIcon name="status/confirm" size={14} /> : <BrandIcon name="action/copy" size={14} />}
                  <span>{copied ? t("chat.copied") : t("relay.copyLink")}</span>
                </Button>
              </div>
            )}
          </div>
        ) : (
          <div className="space-y-2.5 pt-1">
            <div className="text-[13px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
              {t("relay.joinExisting")}
            </div>
            <div className="flex gap-2">
              <Input
                value={pairLink}
                onChange={(e) => setPairLink(e.target.value)}
                placeholder={t("relay.joinPlaceholder")}
              />
              <Button
                variant="primary"
                disabled={busy || !pairLink.trim()}
                onClick={() => {
                  if (!client || !pairLink.trim()) return;
                  setBusy(true);
                  setError(null);
                  void pairRelay(client, pairLink.trim())
                    .then(() => setPairLink(""))
                    .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                    .finally(() => setBusy(false));
                }}
              >
                {t("relay.joinBtn")}
              </Button>
            </div>
          </div>
        )}

        {error && (
          <div className="text-[12.5px]" style={{ color: "var(--pi-danger)" }}>
            {error}
          </div>
        )}
      </Card>

      {relayDevices.length > 0 && (
        <Card className="space-y-3 p-5">
          <div className="text-[14px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {t("relay.devices")}
          </div>
          <div className="space-y-2">
            {relayDevices.map((dev) => (
              <div
                key={dev.deviceId}
                className="flex items-center justify-between gap-3 rounded-[10px] px-3 py-2.5"
                style={{ background: "var(--pi-paper)", border: "0.5px solid var(--pi-hairline)" }}
              >
                <div className="flex min-w-0 items-center gap-2.5">
                  <BrandIcon
                    name={dev.platform.toLowerCase().includes("android") ? "system/device" : "system/desktop"}
                    size={16}
                    style={{ color: "var(--pi-ink-secondary)" }}
                  />
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="truncate text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                        {dev.name}
                      </span>
                      {dev.isSelf && <Badge tone="accent">{t("relay.selfTag")}</Badge>}
                      <Badge tone={dev.online ? "success" : "neutral"}>
                        {dev.online ? t("nav.online") : t("nav.offline")}
                      </Badge>
                    </div>
                    <div className="text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                      {t("relay.deviceSummary", {
                        platform: formatPlatformName(dev.platform, t),
                        count: dev.botCount,
                      })}
                    </div>
                  </div>
                </div>

                <div className="flex shrink-0 items-center gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => {
                      setRenameTarget(dev);
                      setRenameDraft(dev.name);
                    }}
                  >
                    <BrandIcon name="action/pen" size={14} />
                  </Button>
                  {!dev.isSelf && (
                    <Button variant="ghost" size="sm" onClick={() => setRevokeTarget(dev)}>
                      <BrandIcon name="action/delete" size={14} />
                    </Button>
                  )}
                </div>
              </div>
            ))}
          </div>
        </Card>
      )}

      <Dialog
        open={Boolean(renameTarget)}
        onOpenChange={(v) => {
          if (!v) setRenameTarget(null);
        }}
        title={t("relay.renameDevice")}
      >
        <div className="space-y-4">
          <Input value={renameDraft} onChange={(e) => setRenameDraft(e.target.value)} />
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setRenameTarget(null)}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="primary"
              onClick={() => {
                if (client && renameTarget && renameDraft.trim()) {
                  void renameRelayDevice(client, renameTarget.deviceId, renameDraft.trim()).then(
                    () => setRenameTarget(null),
                  );
                }
              }}
            >
              {t("common.save")}
            </Button>
          </div>
        </div>
      </Dialog>

      <ConfirmDialog
        open={Boolean(revokeTarget)}
        onOpenChange={(v) => {
          if (!v) setRevokeTarget(null);
        }}
        title={t("relay.revokeDevice")}
        description={t("relay.revokeConfirm", { name: revokeTarget?.name ?? "" })}
        confirmLabel={t("relay.revokeDevice")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (client && revokeTarget) {
            void revokeRelayDevice(client, revokeTarget.deviceId);
          }
          setRevokeTarget(null);
        }}
      />
    </div>
  );
}
