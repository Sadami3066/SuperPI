import { useState } from "react";
import { IconFolderOpen } from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import { isElectronApp, pickWorkspaceDirectory } from "../../platform/bridge";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { Button, Dialog, Input } from "../../ui";

export function CreateBotDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: (botId: string) => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const createBot = useBotStore((s) => s.createBot);

  const [name, setName] = useState("");
  const [intro, setIntro] = useState("");
  const [dir, setDir] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!client) return;
    setBusy(true);
    setError(null);
    try {
      const created = await createBot(client, {
        name: name.trim() || undefined,
        intro: intro.trim() || undefined,
        dir: dir.trim() || undefined,
      });
      setName("");
      setIntro("");
      setDir("");
      onOpenChange(false);
      onCreated(created.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : t("conn.failedHint"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={t("bot.createTitle")}>
      <form className="space-y-3.5" onSubmit={(e) => void handleCreate(e)}>
        <div className="space-y-1">
          <label className="block text-[12.5px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
            {t("bot.nameLabel")}
          </label>
          <Input
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t("bot.namePlaceholder")}
          />
        </div>

        <div className="space-y-1">
          <label className="block text-[12.5px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
            {t("bot.introLabel")}
          </label>
          <Input
            value={intro}
            onChange={(e) => setIntro(e.target.value)}
            placeholder={t("bot.introPlaceholder")}
          />
        </div>

        <div className="space-y-1">
          <label className="block text-[12.5px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
            {t("bot.dirLabel")}
          </label>
          <div className="flex gap-2">
            <Input
              value={dir}
              onChange={(e) => setDir(e.target.value)}
              placeholder={t("bot.dirPlaceholder")}
              className="flex-1"
            />
            {isElectronApp() && (
              <Button
                variant="secondary"
                onClick={() => {
                  void pickWorkspaceDirectory(t("bot.pickDir")).then((picked) => {
                    if (picked) setDir(picked);
                  });
                }}
              >
                <IconFolderOpen size={15} stroke={1.7} />
                <span>{t("bot.pickDir")}</span>
              </Button>
            )}
          </div>
        </div>

        {error && (
          <div className="text-[12.5px]" style={{ color: "var(--pi-danger)" }}>
            {error}
          </div>
        )}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            {t("bot.cancel")}
          </Button>
          <Button type="submit" variant="primary" disabled={busy}>
            {t("bot.create")}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
