import { useEffect, useState } from "react";
import { IconBrowser, IconMovie } from "@tabler/icons-react";
import type { PresentedItem } from "../../api/types";
import { useI18n } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { Button } from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";

const VIDEO_EXT_RE = /\.(mp4|webm|mov|m4v|mkv)$/i;
const HTML_EXT_RE = /\.html?$/i;

function isHtmlDeliverable(item: PresentedItem): boolean {
  const mime = (item.mime ?? "").toLowerCase();
  return item.type === "html" || mime === "text/html" || HTML_EXT_RE.test(item.name || item.path || "");
}

function isVideoDeliverable(item: PresentedItem): boolean {
  const mime = (item.mime ?? "").toLowerCase();
  return item.type === "video" || mime.startsWith("video/") || VIDEO_EXT_RE.test(item.name || item.path || "");
}

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
    try {
      const blob = await client.fetchSessionArtifact(sessionId, item.path, true);
      triggerBlobDownload(blob, item.name);
    } catch (err) {
      useSessionStore.setState({
        actionError: err instanceof Error ? err.message : "Failed to download file",
      });
    }
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
        background: "var(--pi-canvas)",
        border: "1px solid var(--pi-canvas-border)",
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
          <BrandIcon name="file/image" size={18} />
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
            <BrandIcon name="action/download" size={14} />
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
    try {
      const blob = await client.fetchSessionArtifact(sessionId, item.path, true);
      triggerBlobDownload(blob, item.name);
    } catch (err) {
      useSessionStore.setState({
        actionError: err instanceof Error ? err.message : "Failed to download file",
      });
    }
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
        const isHtml = Boolean(item.path) && isHtmlDeliverable(item);
        const isVideo = Boolean(item.path) && !isHtml && isVideoDeliverable(item);
        return (
          <div
            key={`${item.path || item.id || item.name}-${idx}`}
            className="flex items-center justify-between gap-3 rounded-[14px] px-3.5 py-2.5 transition-[background-color,border-color] duration-150"
            style={{
              background: "var(--pi-chrome)",
              border: "1px solid var(--pi-canvas-border)",
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
                style={{
                  background: isHtml || isVideo ? "var(--pi-accent-soft)" : "var(--pi-wash)",
                  color: isHtml || isVideo ? "var(--pi-accent)" : "var(--pi-ink-secondary)",
                }}
              >
                {isHtml ? (
                  <IconBrowser size={16} stroke={1.7} />
                ) : isVideo ? (
                  <IconMovie size={16} stroke={1.7} />
                ) : (
                  <BrandIcon name="file/plain" size={16} />
                )}
              </div>
              <div className="min-w-0">
                <div className="truncate text-[13px] font-medium" style={{ color: "var(--pi-ink)" }}>
                  {item.title || item.name}
                </div>
                {(isHtml || isVideo) ? (
                  <div className="truncate text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {isHtml ? t("present.htmlDesc") : t("present.videoDesc")}
                  </div>
                ) : (
                  item.path && (
                    <div
                      className="truncate text-[11px]"
                      style={{ color: "var(--pi-ink-tertiary)", fontFamily: "var(--pi-font-mono)" }}
                    >
                      {item.path}
                    </div>
                  )
                )}
              </div>
            </button>
            {item.path && (
              <div className="flex shrink-0 items-center gap-1">
                <Button variant={isHtml || isVideo ? "primary" : "secondary"} size="sm" onClick={() => handleOpenPreview(item)}>
                  <BrandIcon name="navigation/external_link" size={13.5} />
                  <span>
                    {isHtml
                      ? t("present.previewInWorkbench")
                      : isVideo
                        ? t("present.playInWorkbench")
                        : t("present.open")}
                  </span>
                </Button>
                <Button variant="ghost" size="sm" onClick={() => void handleDownload(item)}>
                  <BrandIcon name="action/download" size={14} />
                </Button>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
