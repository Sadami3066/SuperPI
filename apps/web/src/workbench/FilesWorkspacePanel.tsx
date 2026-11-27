import { useWorkbenchStore } from "../stores/workbenchStore";
import FilesPanel from "./FilesPanel";
import PreviewPanel from "./PreviewPanel";
import type { WorkbenchPanelContext } from "./registry";

export default function FilesWorkspacePanel({
  context,
}: {
  context: WorkbenchPanelContext;
}) {
  const filesViewMode = useWorkbenchStore((s) => s.filesViewMode);
  const openFiles = useWorkbenchStore((s) => s.openFiles);
  const setFilesViewMode = useWorkbenchStore((s) => s.setFilesViewMode);

  if (filesViewMode === "editor" && openFiles.length > 0) {
    return (
      <PreviewPanel
        context={context}
        onBackToFiles={() => setFilesViewMode("tree")}
      />
    );
  }

  return (
    <FilesPanel
      context={context}
      openFilesCount={openFiles.length}
      onOpenEditor={() => setFilesViewMode("editor")}
    />
  );
}
