import { useState } from "react";
import { IconArchive, IconRestore, IconTrash } from "@tabler/icons-react";
import type { SessionSummary } from "../../api/types";
import { useI18n } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { Button, Card, ConfirmDialog, EmptyState } from "../../ui";

export function ArchivedSettings({
  onOpenSession,
}: {
  onOpenSession: () => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const locale = useSettingsStore((s) => s.locale);
  const archivedSessions = useSessionStore((s) => s.archivedSessions);
  const selectSession = useSessionStore((s) => s.selectSession);
  const toggleArchiveSession = useSessionStore((s) => s.toggleArchiveSession);
  const deleteSession = useSessionStore((s) => s.deleteSession);

  const [deleteTarget, setDeleteTarget] = useState<SessionSummary | null>(null);

  if (archivedSessions.length === 0) {
    return (
      <Card>
        <EmptyState icon={<IconArchive size={20} />} title={t("archived.empty")} />
      </Card>
    );
  }

  return (
    <>
      <Card className="divide-y divide-[var(--pi-hairline)] overflow-hidden">
        {archivedSessions.map((sess) => (
          <div key={sess.id} className="flex items-center justify-between gap-3 px-4 py-3">
            <button
              type="button"
              onClick={() => {
                if (!client) return;
                onOpenSession();
                void selectSession(client, sess.id, locale);
              }}
              className="min-w-0 flex-1 text-left"
            >
              <div className="truncate text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                {sess.title || t("nav.untitledChat")}
              </div>
              <div className="truncate text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {sess.cwd}
              </div>
            </button>
            <div className="flex shrink-0 items-center gap-1.5">
              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  if (client) void toggleArchiveSession(client, sess.id, false);
                }}
              >
                <IconRestore size={14} />
                <span>{t("nav.unarchive")}</span>
              </Button>
              <Button variant="ghost" size="sm" onClick={() => setDeleteTarget(sess)}>
                <IconTrash size={14} />
              </Button>
            </div>
          </div>
        ))}
      </Card>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(v) => {
          if (!v) setDeleteTarget(null);
        }}
        title={t("nav.deleteConfirmTitle")}
        description={t("nav.deleteConfirmDesc", {
          title: deleteTarget?.title || t("nav.untitledChat"),
        })}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (client && deleteTarget) {
            void deleteSession(client, deleteTarget.id);
          }
          setDeleteTarget(null);
        }}
      />
    </>
  );
}
