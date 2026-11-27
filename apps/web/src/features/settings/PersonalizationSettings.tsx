import { useEffect, useRef, useState } from "react";
import { useI18n } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { Button, Input, Textarea } from "../../ui";

export function PersonalizationSettings() {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const hostSettings = useSettingsStore((s) => s.hostSettings);
  const patchHostSettings = useSettingsStore((s) => s.patchHostSettings);

  const [nicknameDraft, setNicknameDraft] = useState(hostSettings?.userNickname ?? "");
  const [instructionDraft, setInstructionDraft] = useState(
    hostSettings?.customSystemInstruction ?? "",
  );
  const [nicknameSaved, setNicknameSaved] = useState(false);
  const [instructionSaved, setInstructionSaved] = useState(false);
  const timersRef = useRef<ReturnType<typeof setTimeout>[]>([]);

  useEffect(() => {
    return () => {
      for (const id of timersRef.current) clearTimeout(id);
      timersRef.current = [];
    };
  }, []);

  useEffect(() => {
    if (hostSettings) {
      setNicknameDraft(hostSettings.userNickname ?? "");
      setInstructionDraft(hostSettings.customSystemInstruction ?? "");
    }
  }, [hostSettings]);

  if (!hostSettings) {
    return (
      <div className="py-10 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
        {t("common.loading")}
      </div>
    );
  }

  const nicknameDirty = nicknameDraft.trim() !== (hostSettings.userNickname ?? "").trim();
  const instructionDirty =
    instructionDraft.trim() !== (hostSettings.customSystemInstruction ?? "").trim();

  return (
    <div className="divide-y divide-[var(--pi-hairline)]">
      <div className="flex flex-col justify-between gap-3 py-4 sm:flex-row sm:items-center">
        <div className="min-w-0">
          <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
            {t("settings.nicknameLabel")}
          </div>
          <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
            {t("settings.nicknameDesc")}
          </div>
        </div>

        <div className="flex items-center gap-2 sm:w-[290px]">
          <Input
            value={nicknameDraft}
            maxLength={64}
            placeholder={t("settings.nicknamePlaceholder")}
            onChange={(e) => {
              setNicknameDraft(e.target.value);
              setNicknameSaved(false);
            }}
          />
          <Button
            variant="secondary"
            size="sm"
            disabled={!client || !nicknameDirty}
            onClick={() => {
              if (!client) return;
              void patchHostSettings(client, { userNickname: nicknameDraft.trim() }).then((ok) => {
                if (!ok) return;
                setNicknameSaved(true);
                const id = setTimeout(() => setNicknameSaved(false), 1600);
                timersRef.current.push(id);
              });
            }}
          >
            {nicknameSaved ? t("bot.saved") : t("common.save")}
          </Button>
        </div>
      </div>

      <div className="space-y-3 py-5">
        <div>
          <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
            {t("settings.customInstructionLabel")}
          </div>
          <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
            {t("settings.customInstructionDesc")}
          </div>
        </div>

        <Textarea
          rows={5}
          value={instructionDraft}
          placeholder={t("settings.customInstructionPlaceholder")}
          onChange={(e) => {
            setInstructionDraft(e.target.value);
            setInstructionSaved(false);
          }}
        />

        <div className="flex justify-end">
          <Button
            variant="primary"
            size="sm"
            disabled={!client || !instructionDirty}
            onClick={() => {
              if (!client) return;
              void patchHostSettings(client, {
                customSystemInstruction: instructionDraft.trim(),
              }).then((ok) => {
                if (!ok) return;
                setInstructionSaved(true);
                const id = setTimeout(() => setInstructionSaved(false), 1600);
                timersRef.current.push(id);
              });
            }}
          >
            {instructionSaved ? t("bot.saved") : t("common.save")}
          </Button>
        </div>
      </div>
    </div>
  );
}
