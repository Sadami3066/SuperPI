import { useEffect, useState } from "react";
import { IconDownload, IconExternalLink, IconFile, IconPhoto } from "@tabler/icons-react";
import type { PresentedItem } from "../../api/types";
import { useI18n } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { Button } from "../../ui";

function triggerBlobDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1200);
}

function ImageArtifactCard({
  sessionId,
  item,
}: {
  sessionId: string;
  item: PresentedItem;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const [srcUrl, setSrcUrl] = useState<string | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!client || !item.path) return;
    let revoked = false;
    let createdUrl: string | null = null;
    void client
      .fetchSessionArtifact(sessionId, item.path, false)
      .then((blob) => {
        if (revoked) return;
        createdUrl = URL.createObjectURL(blob);
        setSrcUrl(createdUrl);
      })
      .catch(() => {
        if (!revoked) setError(true);
      });

    return () => {
      revoked = true;
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [client, item.path, sessionId]);

  const handleDownload = async () => {
    if (!client || !item.path) return;
    const blob = await client.fetchSessionArtifact(sessionId, item.path, true);
    triggerBlobDownload(blob, item.name);
  };

  const handleOpenPreview = () => {
    if (!item.path) return;
    useWorkbenchStore.getState().openFileInPreview({
      source: "artifact",
      sessionId,
      path: item.path,
      name: item.name,
      mime: item.mime,
    });
  };

  return (
    <div
      className="overflow-hidden rounded-[14px]"
      style={{
        background: "var(--pi-surface)",
        border: "0.5px solid var(--pi-hairline)",
      }}
    >
      {srcUrl ? (
        <button type="button" onClick={handleOpenPreview} className="block w-full">
          <img
            src={srcUrl}
            alt={item.title || item.name}
            className="max-h-[360px] w-full object-contain"
            style={{ background: "var(--pi-code-bg)" }}
          />
        </button>
      ) : (
        <div
          className="flex h-32 items-center justify-center gap-2 text-[12.5px]"
          style={{ color: "var(--pi-ink-tertiary)" }}
        >
          <IconPhoto size={18} stroke={1.6} />
          <span>{error ? t("present.cardUnavailable") : t("common.loading")}</span>
        </div>
      )}
      <div
        className="flex items-center justify-between gap-2 px-3 py-2"
        style={{ borderTop: "0.5px solid var(--pi-hairline)" }}
      >
        <span className="truncate text-[12.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
          {item.title || item.name}
        </span>
        {item.path && (
          <Button variant="ghost" size="sm" onClick={() => void handleDownload()}>
            <IconDownload size={14} stroke={1.7} />
            <span>{t("present.download")}</span>
          </Button>
        )}
      </div>
    </div>
  );
}

export function PresentCard({
  sessionId,
  items,
}: {
  sessionId: string;
  items: PresentedItem[];
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);

  const handleDownload = async (item: PresentedItem) => {
    if (!client || !item.path) return;
    const blob = await client.fetchSessionArtifact(sessionId, item.path, true);
    triggerBlobDownload(blob, item.name);
  };

  const handleOpenPreview = (item: PresentedItem) => {
    if (!item.path) return;
    useWorkbenchStore.getState().openFileInPreview({
      source: "artifact",
      sessionId,
      path: item.path,
      name: item.name,
      mime: item.mime,
    });
  };

  return (
    <div className="my-2.5 space-y-2">
      {items.map((item, idx) => {
        if (item.type === "image" && item.path) {
          return (
            <ImageArtifactCard
              key={`${item.path}-${idx}`}
              sessionId={sessionId}
              item={item}
            />
          );
        }
        return (
          <div
            key={`${item.path || item.id || item.name}-${idx}`}
            className="flex items-center justify-between gap-3 rounded-[14px] px-3.5 py-2.5"
            style={{
              background: "var(--pi-surface)",
              border: "0.5px solid var(--pi-hairline)",
            }}
          >
            <button
              type="button"
              disabled={!item.path}
              onClick={() => handleOpenPreview(item)}
              className="flex min-w-0 flex-1 items-center gap-2.5 text-left"
            >
              <div
                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-[9px]"
                style={{ background: "var(--pi-wash)", color: "var(--pi-ink-secondary)" }}
              >
                <IconFile size={16} stroke={1.65} />
              </div>
              <div className="min-w-0">
                <div className="truncate text-[13px] font-medium" style={{ color: "var(--pi-ink)" }}>
                  {item.title || item.name}
                </div>
                {item.path && (
                  <div
                    className="truncate text-[11px]"
                    style={{ color: "var(--pi-ink-tertiary)", fontFamily: "var(--pi-font-mono)" }}
                  >
                    {item.path}
                  </div>
                )}
              </div>
            </button>
            {item.path && (
              <div className="flex shrink-0 items-center gap-1">
                <Button variant="secondary" size="sm" onClick={() => handleOpenPreview(item)}>
                  <IconExternalLink size={13.5} stroke={1.7} />
                  <span>{t("present.open")}</span>
                </Button>
                <Button variant="ghost" size="sm" onClick={() => void handleDownload(item)}>
                  <IconDownload size={14} stroke={1.7} />
                </Button>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
