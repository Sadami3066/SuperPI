import { useCallback, useEffect, useRef, useState } from "react";
import { IconEye, IconRefresh, IconTrash, IconUpload } from "@tabler/icons-react";
import type { HostInstalledSkillDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { Badge, Button, Card, ConfirmDialog, Dialog, IconButton, Switch } from "../../ui";

const MAX_ZIP_BYTES = 10 * 1024 * 1024;

export function SkillsSettings() {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);

  const [skills, setSkills] = useState<HostInstalledSkillDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [inspectSkill, setInspectSkill] = useState<{ name: string; content: string } | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<HostInstalledSkillDto | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const loadSkills = useCallback(async () => {
    if (!client) return;
    setLoading(true);
    setError(null);
    try {
      const list = await client.listSkills();
      setSkills(list);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load skills");
    } finally {
      setLoading(false);
    }
  }, [client]);

  useEffect(() => {
    void loadSkills();
  }, [loadSkills]);

  const handleInstallZip = async (file: File | undefined) => {
    if (!client || !file) return;
    if (file.size <= 0 || file.size > MAX_ZIP_BYTES) {
      setError(t("skills.sizeLimit"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const dataUrl = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result ?? ""));
        reader.onerror = () => reject(new Error("Failed to read archive"));
        reader.readAsDataURL(file);
      });
      const b64 = dataUrl.includes("base64,") ? dataUrl.split("base64,")[1] : dataUrl;
      await client.installSkillZip(file.name, b64);
      await loadSkills();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to install skill");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-2">
        <div className="text-[12.5px]" style={{ color: "var(--pi-ink-secondary)" }}>
          {t("skills.summary", { count: skills.length })}
        </div>

        <div className="flex items-center gap-1.5">
          <input
            ref={fileInputRef}
            type="file"
            accept=".zip"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              e.target.value = "";
              void handleInstallZip(file);
            }}
          />
          <Button
            variant="secondary"
            size="sm"
            disabled={busy}
            onClick={() => fileInputRef.current?.click()}
          >
            <IconUpload size={14} />
            <span>{t("skills.installZip")}</span>
          </Button>
          <IconButton size="sm" label={t("common.refresh")} onClick={() => void loadSkills()}>
            <IconRefresh size={14} />
          </IconButton>
        </div>
      </div>

      {error && (
        <div
          className="flex items-center justify-between gap-2 rounded-[10px] px-3 py-2 text-[12.5px]"
          style={{ background: "var(--pi-paper)", color: "var(--pi-danger)" }}
        >
          <span className="truncate">{error}</span>
          <Button variant="ghost" size="sm" onClick={() => void loadSkills()}>
            {t("conn.retry")}
          </Button>
        </div>
      )}

      {loading && skills.length === 0 ? (
        <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("common.loading")}
        </div>
      ) : skills.length === 0 ? (
        <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("skills.empty")}
        </div>
      ) : (
        <div className="space-y-2.5">
          {skills.map((skill) => (
            <Card key={skill.id} className="space-y-2 p-4">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-[14px] font-semibold" style={{ color: "var(--pi-ink)" }}>
                      {skill.name}
                    </span>
                    <Badge tone="neutral">{skill.source}</Badge>
                  </div>
                  {skill.description && (
                    <p className="text-[12.5px] leading-[1.5]" style={{ color: "var(--pi-ink-secondary)" }}>
                      {skill.description}
                    </p>
                  )}
                </div>

                <Switch
                  checked={skill.enabled}
                  disabled={busy}
                  onCheckedChange={(checked) => {
                    if (!client) return;
                    setBusy(true);
                    void client
                      .toggleSkill(skill.name, checked)
                      .then(() => loadSkills())
                      .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                      .finally(() => setBusy(false));
                  }}
                />
              </div>

              <div
                className="flex items-center justify-end gap-1.5 pt-2"
                style={{ borderTop: "0.5px solid var(--pi-hairline)" }}
              >
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    if (!client) return;
                    void client
                      .getSkillContent(skill.id)
                      .then((res) => setInspectSkill({ name: skill.name, content: res.content }))
                      .catch((err) => setError(err instanceof Error ? err.message : "Failed"));
                  }}
                >
                  <IconEye size={13.5} />
                  <span>{t("skills.view")}</span>
                </Button>
                {skill.removable && (
                  <IconButton
                    size="sm"
                    label={t("common.delete")}
                    onClick={() => setDeleteTarget(skill)}
                  >
                    <IconTrash size={14} />
                  </IconButton>
                )}
              </div>
            </Card>
          ))}
        </div>
      )}

      <Dialog
        open={Boolean(inspectSkill)}
        onOpenChange={(v) => {
          if (!v) setInspectSkill(null);
        }}
        title={inspectSkill?.name}
        maxWidthClass="max-w-[640px]"
      >
        {inspectSkill && (
          <pre
            className="max-h-[65vh] overflow-auto rounded-[10px] p-3.5 text-[12px] whitespace-pre-wrap"
            style={{
              background: "var(--pi-paper)",
              color: "var(--pi-ink)",
              fontFamily: "var(--pi-font-mono)",
            }}
          >
            {inspectSkill.content}
          </pre>
        )}
      </Dialog>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(v) => {
          if (!v) setDeleteTarget(null);
        }}
        title={t("skills.deleteTitle")}
        description={deleteTarget?.name || ""}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (!client || !deleteTarget) return;
          const id = deleteTarget.id;
          setDeleteTarget(null);
          void client.deleteSkill(id).then(() => loadSkills());
        }}
      />
    </div>
  );
}
