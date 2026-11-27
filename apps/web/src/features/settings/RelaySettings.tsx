import { useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { formatPlatformName } from "../../api/formatters";
import type { HostRelayDeviceDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { Badge, Button, Card, ConfirmDialog, Dialog, Input, NoticeBar, Switch } from "../../ui";
import { BrandIcon, DevicePlatformIcon } from "../shared/BrandAssets";

type RenameTarget = {
  deviceId: string;
  name: string;
};

export function RelaySettings() {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const hostSettings = useSettingsStore((s) => s.hostSettings);
  const relayStatus = useSettingsStore((s) => s.relayStatus);
  const relayDevices = useSettingsStore((s) => s.relayDevices);
  const generatePairCode = useSettingsStore((s) => s.generatePairCode);
  const pairRelay = useSettingsStore((s) => s.pairRelay);
  const logoutRelay = useSettingsStore((s) => s.logoutRelay);
  const renameRelayDevice = useSettingsStore((s) => s.renameRelayDevice);
  const revokeRelayDevice = useSettingsStore((s) => s.revokeRelayDevice);
  const setRelayAllowRemote = useSettingsStore((s) => s.setRelayAllowRemote);

  const [pairLink, setPairLink] = useState("");
  const [deviceNameInput, setDeviceNameInput] = useState(() => hostSettings?.deviceName ?? "");
  const [deviceNameDirty, setDeviceNameDirty] = useState(false);
  const [loginSecret, setLoginSecret] = useState("");
  const [qrLink, setQrLink] = useState<string | null>(null);
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [renameTarget, setRenameTarget] = useState<RenameTarget | null>(null);
  const [renameDraft, setRenameDraft] = useState("");
  const [renameError, setRenameError] = useState<string | null>(null);
  const [revokeTarget, setRevokeTarget] = useState<HostRelayDeviceDto | null>(null);
  const [confirmLogout, setConfirmLogout] = useState(false);
  const copyTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (!deviceNameDirty && hostSettings?.deviceName) {
      setDeviceNameInput(hostSettings.deviceName);
    }
  }, [deviceNameDirty, hostSettings?.deviceName]);

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
  const stateRaw = (relayStatus?.state || "").toLowerCase();
  const statusTone: "success" | "accent" | "warning" | "danger" | "neutral" = !enrolled
    ? "neutral"
    : stateRaw === "connected" || stateRaw === "online"
      ? "success"
      : stateRaw === "connecting" || stateRaw === "reconnecting"
        ? "accent"
        : stateRaw === "unauthorized"
          ? "danger"
          : "warning";
  const statusLabel = !enrolled
    ? t("relay.statusDisconnected")
    : stateRaw === "connected" || stateRaw === "online"
      ? t("relay.statusConnected")
      : stateRaw === "connecting" || stateRaw === "reconnecting"
        ? t("relay.stateConnecting")
        : stateRaw === "unauthorized"
          ? t("relay.stateUnauthorized")
          : t("relay.stateOffline");

  const selfDeviceId = relayStatus?.deviceId ?? "";
  const selfDeviceName =
    relayStatus?.deviceName?.trim() || hostSettings?.deviceName?.trim() || t("relay.thisDevice");

  const submitRename = () => {
    if (!client || !renameTarget || !renameDraft.trim() || busy) return;
    setBusy(true);
    setRenameError(null);
    void renameRelayDevice(client, renameTarget.deviceId, renameDraft.trim())
      .then(() => {
        setRenameTarget(null);
        setRenameError(null);
      })
      .catch((err) => {
        setRenameError(err instanceof Error ? err.message : "Failed to rename device");
      })
      .finally(() => setBusy(false));
  };

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
          <Badge tone={statusTone}>{statusLabel}</Badge>
        </div>

        {enrolled && selfDeviceId && (
          <div
            className="flex items-center justify-between gap-3 rounded-[10px] px-3 py-2.5"
            style={{ background: "var(--pi-paper)", border: "1px solid var(--pi-canvas-border)" }}
          >
            <div className="flex min-w-0 items-center gap-2.5">
              <BrandIcon
                name="system/desktop"
                size={16}
                style={{ color: "var(--pi-ink-secondary)" }}
              />
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <span className="truncate text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                    {selfDeviceName}
                  </span>
                  <Badge tone="accent">{t("relay.selfTag")}</Badge>
                </div>
                <div className="text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {t("relay.thisDevice")}
                </div>
              </div>
            </div>
            <Button
              variant="ghost"
              size="sm"
              disabled={busy}
              onClick={() => {
                setRenameError(null);
                setRenameTarget({ deviceId: selfDeviceId, name: selfDeviceName });
                setRenameDraft(selfDeviceName);
              }}
            >
              <BrandIcon name="action/pen" size={14} />
              <span>{t("nav.rename")}</span>
            </Button>
          </div>
        )}

        <div className="flex items-center justify-between gap-3">
          <span className="text-[13.5px]" style={{ color: "var(--pi-ink)" }}>
            {t("relay.allowRemote")}
          </span>
          <Switch
            checked={Boolean(relayStatus?.allowRemote)}
            disabled={busy}
            ariaLabel={t("relay.allowRemote")}
            onCheckedChange={(checked) => {
              if (!client) return;
              setBusy(true);
              setError(null);
              void setRelayAllowRemote(client, checked)
                .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                .finally(() => setBusy(false));
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
                onClick={() => setConfirmLogout(true)}
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
          <div className="space-y-3 pt-1">
            <div className="text-[13px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
              {t("relay.joinExisting")}
            </div>
            <div className="space-y-2.5">
              <Input
                value={pairLink}
                onChange={(e) => setPairLink(e.target.value)}
                placeholder={t("relay.joinPlaceholder")}
              />
              <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
                <div className="space-y-1">
                  <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                    {t("relay.deviceName")}
                  </label>
                  <Input
                    value={deviceNameInput}
                    onChange={(e) => {
                      setDeviceNameDirty(true);
                      setDeviceNameInput(e.target.value);
                    }}
                    placeholder={t("relay.deviceNamePlaceholder")}
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                    {t("relay.loginSecret")}
                  </label>
                  <Input
                    type="password"
                    value={loginSecret}
                    onChange={(e) => setLoginSecret(e.target.value)}
                    placeholder={t("relay.loginSecretPlaceholder")}
                  />
                </div>
              </div>
              <div className="flex justify-end">
                <Button
                  variant="primary"
                  disabled={busy || !pairLink.trim()}
                  onClick={() => {
                    if (!client || !pairLink.trim()) return;
                    setBusy(true);
                    setError(null);
                    void pairRelay(
                      client,
                      pairLink.trim(),
                      deviceNameInput.trim() || undefined,
                      loginSecret.trim() || undefined,
                    )
                      .then(() => {
                        setPairLink("");
                        setLoginSecret("");
                      })
                      .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                      .finally(() => setBusy(false));
                  }}
                >
                  {t("relay.joinBtn")}
                </Button>
              </div>
            </div>
          </div>
        )}

        {error && <NoticeBar tone="danger" message={error} />}
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
                  <DevicePlatformIcon
                    platform={dev.platform}
                    capabilities={dev.capabilities}
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
                        platform: formatPlatformName(dev.platform, t, dev.capabilities),
                        count: dev.botCount,
                      })}
                    </div>
                  </div>
                </div>

                <div className="flex shrink-0 items-center gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={busy}
                    onClick={() => {
                      setRenameError(null);
                      setRenameTarget({ deviceId: dev.deviceId, name: dev.name });
                      setRenameDraft(dev.name);
                    }}
                  >
                    <BrandIcon name="action/pen" size={14} />
                  </Button>
                  {!dev.isSelf && (
                    <Button variant="ghost" size="sm" disabled={busy} onClick={() => setRevokeTarget(dev)}>
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
          if (!v && !busy) {
            setRenameTarget(null);
            setRenameError(null);
          }
        }}
        title={t("relay.renameDevice")}
      >
        <div className="space-y-4">
          <Input
            autoFocus
            value={renameDraft}
            onChange={(e) => setRenameDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.nativeEvent.isComposing || e.keyCode === 229) return;
              if (e.key === "Enter") {
                e.preventDefault();
                submitRename();
              }
            }}
          />
          {renameError && <NoticeBar tone="danger" message={renameError} />}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" disabled={busy} onClick={() => setRenameTarget(null)}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="primary"
              disabled={busy || !renameDraft.trim()}
              onClick={submitRename}
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
          if (!client || !revokeTarget) return;
          const targetId = revokeTarget.deviceId;
          setRevokeTarget(null);
          setBusy(true);
          setError(null);
          void revokeRelayDevice(client, targetId)
            .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
            .finally(() => setBusy(false));
        }}
      />

      <ConfirmDialog
        open={confirmLogout}
        onOpenChange={setConfirmLogout}
        title={t("relay.logoutConfirmTitle")}
        description={t("relay.logoutConfirmDesc")}
        confirmLabel={t("relay.logout")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (!client) return;
          setConfirmLogout(false);
          setBusy(true);
          setError(null);
          void logoutRelay(client)
            .then(() => setQrLink(null))
            .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
            .finally(() => setBusy(false));
        }}
      />
    </div>
  );
}
