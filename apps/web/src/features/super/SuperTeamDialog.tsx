import { useEffect, useMemo, useRef, useState } from "react";
import type { HostSuperSettingsDto, HostSuperTeamBotDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useSuperStore } from "../../stores/superStore";
import {
  Badge,
  Button,
  ConfirmDialog,
  Dialog,
  Input,
  NoticeBar,
  Switch,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  Textarea,
} from "../../ui";
import { BotAvatar } from "../shared/BrandAssets";

const DEFAULT_SUPER_SETTINGS: HostSuperSettingsDto = {
  maxConcurrentWorkers: 3,
  workerMaxSlotsPerBot: 2,
  workerIdleCleanupMinutes: 60,
  workerStallTimeoutMinutes: 10,
  questionTimeoutMinutes: 30,
  coalesceWindowMs: 1500,
  receiptMaxChars: 4000,
  workerJobWake: true,
  workerCompactionOverrideEnabled: false,
  workerCompactionTriggerPercent: 75,
};

export function SuperTeamDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const dark = useSettingsStore((s) => s.dark);

  const team = useSuperStore((s) => s.team);
  const superSettings = useSuperStore((s) => s.superSettings);
  const refreshTeam = useSuperStore((s) => s.refreshTeam);
  const setTeamBot = useSuperStore((s) => s.setTeamBot);
  const refreshSuperSettings = useSuperStore((s) => s.refreshSuperSettings);
  const updateSuperSettings = useSuperStore((s) => s.updateSuperSettings);

  const [tab, setTab] = useState("team");
  const [teamLoading, setTeamLoading] = useState(false);
  const [agentsDoc, setAgentsDoc] = useState("");
  const [settingsDraft, setSettingsDraft] = useState<HostSuperSettingsDto | null>(null);
  const [showAdvancedLimits, setShowAdvancedLimits] = useState(false);
  const [confirmResetDefaults, setConfirmResetDefaults] = useState(false);
  const [busy, setBusy] = useState(false);
  const [savedNotice, setSavedNotice] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const savedTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (savedTimerRef.current !== null) clearTimeout(savedTimerRef.current);
    };
  }, []);

  useEffect(() => {
    if (!open || !client) return;
    setError(null);
    setSavedNotice(false);
    setTeamLoading(true);
    void refreshTeam(client).finally(() => setTeamLoading(false));
    void refreshSuperSettings(client).catch((err) =>
      setError(err instanceof Error ? err.message : "Failed to load settings"),
    );
    void client
      .getSuperAgentsDoc()
      .then((doc) => setAgentsDoc(doc.content ?? ""))
      .catch(() => setAgentsDoc(""));
  }, [client, open, refreshSuperSettings, refreshTeam]);

  useEffect(() => {
    if (superSettings) {
      setSettingsDraft(superSettings);
    }
  }, [superSettings]);

  const groupedTeam = useMemo(() => {
    const local: HostSuperTeamBotDto[] = [];
    const remoteMap = new Map<string, { label: string; online: boolean; members: HostSuperTeamBotDto[] }>();
    for (const member of team) {
      if (!member.isRemote) {
        local.push(member);
      } else {
        const key = member.deviceId || member.deviceName || "remote";
        const cur = remoteMap.get(key);
        if (cur) {
          cur.online = cur.online || member.online;
          cur.members.push(member);
        } else {
          remoteMap.set(key, {
            label: member.deviceName || t("nav.remoteBots"),
            online: member.online,
            members: [member],
          });
        }
      }
    }
    const groups: Array<{
      key: string;
      label: string;
      isRemote: boolean;
      online: boolean;
      members: HostSuperTeamBotDto[];
    }> = [];
    if (local.length > 0) {
      groups.push({
        key: "__local__",
        label: t("super.teamGroupLocal"),
        isRemote: false,
        online: true,
        members: local,
      });
    }
    for (const [key, g] of remoteMap.entries()) {
      groups.push({
        key,
        label: g.label,
        isRemote: true,
        online: g.online,
        members: g.members,
      });
    }
    return groups;
  }, [t, team]);

  const showSaved = () => {
    setSavedNotice(true);
    if (savedTimerRef.current !== null) clearTimeout(savedTimerRef.current);
    savedTimerRef.current = setTimeout(() => setSavedNotice(false), 1600);
  };

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={t("super.teamDialogTitle")}
      maxWidthClass="max-w-[640px]"
    >
      <Tabs value={tab} onValueChange={setTab} className="space-y-4">
        <TabsList>
          <TabsTrigger value="team">{t("super.tabTeam")}</TabsTrigger>
          <TabsTrigger value="instructions">{t("super.tabInstructions")}</TabsTrigger>
          <TabsTrigger value="limits">{t("super.tabLimits")}</TabsTrigger>
        </TabsList>

        <TabsContent value="team" className="max-h-[420px] space-y-4 overflow-y-auto pr-1">
          {team.length === 0 ? (
            <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
              {teamLoading ? t("common.loading") : t("super.teamEmpty")}
            </div>
          ) : (
            groupedTeam.map((group) => (
              <div key={group.key} className="space-y-2">
                <div className="flex items-center justify-between px-1">
                  <span
                    className="text-[11.5px] font-semibold tracking-wide"
                    style={{ color: "var(--pi-ink-tertiary)" }}
                  >
                    {group.label}
                  </span>
                  {group.isRemote && (
                    <Badge tone={group.online ? "success" : "neutral"}>
                      {group.online ? t("nav.online") : t("nav.offline")}
                    </Badge>
                  )}
                </div>
                <div className="space-y-2">
                  {group.members.map((member) => (
                    <div
                      key={member.ref}
                      className="flex items-center justify-between gap-3 rounded-[12px] px-3.5 py-2.5"
                      style={{
                        background: "var(--pi-paper)",
                        border: "0.5px solid var(--pi-hairline)",
                      }}
                    >
                      <div className="flex min-w-0 items-center gap-3">
                        <BotAvatar
                          avatar={member.avatar}
                          name={member.name}
                          isRemote={member.isRemote}
                          dark={dark}
                          size={28}
                        />
                        <div className="min-w-0">
                          <div className="flex flex-wrap items-center gap-1.5">
                            <span className="truncate text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                              {member.name}
                            </span>
                            {member.usedWorkerSlots > 0 && (
                              <Badge tone="accent">
                                {member.usedWorkerSlots}/{member.maxWorkerSlots}
                              </Badge>
                            )}
                          </div>
                          {member.intro && (
                            <div className="truncate text-[12px]" style={{ color: "var(--pi-ink-secondary)" }}>
                              {member.intro}
                            </div>
                          )}
                          <div className="mt-0.5 flex flex-wrap gap-2 text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                            <span>{t("super.memberSkills", { count: member.skills.length })}</span>
                            <span>{t("super.memberMcp", { count: member.mcpServers.length })}</span>
                          </div>
                        </div>
                      </div>

                      <Switch
                        checked={member.enabledInSuper}
                        disabled={busy || !member.supportsSuperWorker}
                        onCheckedChange={(checked) => {
                          if (!client) return;
                          setBusy(true);
                          setError(null);
                          void setTeamBot(client, member.ref, checked)
                            .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                            .finally(() => setBusy(false));
                        }}
                      />
                    </div>
                  ))}
                </div>
              </div>
            ))
          )}
        </TabsContent>

        <TabsContent value="instructions" className="space-y-3">
          <Textarea
            rows={11}
            value={agentsDoc}
            onChange={(e) => setAgentsDoc(e.target.value)}
            placeholder={t("bot.instructionsPlaceholder")}
            style={{ fontFamily: "var(--pi-font-mono)" }}
          />
          <div className="flex justify-end gap-2">
            <Button
              variant="ghost"
              size="sm"
              disabled={busy}
              onClick={() => {
                if (!client) return;
                setBusy(true);
                setError(null);
                void client
                  .deleteSuperAgentsDoc()
                  .then(() => {
                    setAgentsDoc("");
                    showSaved();
                  })
                  .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                  .finally(() => setBusy(false));
              }}
            >
              {t("super.clearInstructions")}
            </Button>
            <Button
              variant="primary"
              size="sm"
              disabled={busy}
              onClick={() => {
                if (!client) return;
                setBusy(true);
                setError(null);
                void client
                  .saveSuperAgentsDoc(agentsDoc)
                  .then(() => showSaved())
                  .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                  .finally(() => setBusy(false));
              }}
            >
              {savedNotice ? t("bot.saved") : t("common.save")}
            </Button>
          </div>
        </TabsContent>

        <TabsContent value="limits" className="space-y-3.5">
          {settingsDraft ? (
            <>
              <div className="space-y-1">
                <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                  {t("super.limitConcurrent")}
                </label>
                <Input
                  type="number"
                  min={1}
                  max={10}
                  value={settingsDraft.maxConcurrentWorkers}
                  onChange={(e) =>
                    setSettingsDraft({
                      ...settingsDraft,
                      maxConcurrentWorkers: Number(e.target.value) || 1,
                    })
                  }
                />
              </div>

              <div>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setShowAdvancedLimits((v) => !v)}
                >
                  {showAdvancedLimits ? t("super.teamAdvancedHide") : t("super.teamAdvancedShow")}
                </Button>
              </div>

              {showAdvancedLimits && (
                <div className="space-y-3.5 rounded-[12px] p-3" style={{ background: "var(--pi-paper)", border: "0.5px solid var(--pi-hairline)" }}>
                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                    <div className="space-y-1">
                      <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                        {t("super.limitPerBot")}
                      </label>
                      <Input
                        type="number"
                        min={1}
                        max={16}
                        value={settingsDraft.workerMaxSlotsPerBot}
                        onChange={(e) =>
                          setSettingsDraft({
                            ...settingsDraft,
                            workerMaxSlotsPerBot: Number(e.target.value) || 1,
                          })
                        }
                      />
                    </div>
                    <div className="space-y-1">
                      <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                        {t("super.limitIdleMinutes")}
                      </label>
                      <Input
                        type="number"
                        min={5}
                        max={1440}
                        value={settingsDraft.workerIdleCleanupMinutes}
                        onChange={(e) =>
                          setSettingsDraft({
                            ...settingsDraft,
                            workerIdleCleanupMinutes: Number(e.target.value) || 5,
                          })
                        }
                      />
                    </div>
                    <div className="space-y-1">
                      <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                        {t("super.limitStallMinutes")}
                      </label>
                      <Input
                        type="number"
                        min={0}
                        max={60}
                        value={settingsDraft.workerStallTimeoutMinutes}
                        onChange={(e) =>
                          setSettingsDraft({
                            ...settingsDraft,
                            workerStallTimeoutMinutes: Number(e.target.value) || 0,
                          })
                        }
                      />
                    </div>
                    <div className="space-y-1">
                      <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                        {t("super.questionTimeout")}
                      </label>
                      <Input
                        type="number"
                        min={1}
                        max={180}
                        value={settingsDraft.questionTimeoutMinutes}
                        onChange={(e) =>
                          setSettingsDraft({
                            ...settingsDraft,
                            questionTimeoutMinutes: Number(e.target.value) || 30,
                          })
                        }
                      />
                    </div>
                    <div className="space-y-1">
                      <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                        {t("super.coalesceWindow")}
                      </label>
                      <Input
                        type="number"
                        min={0}
                        max={30000}
                        value={settingsDraft.coalesceWindowMs}
                        onChange={(e) =>
                          setSettingsDraft({
                            ...settingsDraft,
                            coalesceWindowMs: Number(e.target.value) || 0,
                          })
                        }
                      />
                    </div>
                    <div className="space-y-1">
                      <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                        {t("super.receiptMaxChars")}
                      </label>
                      <Input
                        type="number"
                        min={500}
                        max={32000}
                        value={settingsDraft.receiptMaxChars}
                        onChange={(e) =>
                          setSettingsDraft({
                            ...settingsDraft,
                            receiptMaxChars: Number(e.target.value) || 4000,
                          })
                        }
                      />
                    </div>
                  </div>

                  <div className="flex items-center justify-between gap-3 pt-1">
                    <span className="text-[13px]" style={{ color: "var(--pi-ink)" }}>
                      {t("super.limitJobWake")}
                    </span>
                    <Switch
                      checked={settingsDraft.workerJobWake}
                      onCheckedChange={(checked) =>
                        setSettingsDraft({ ...settingsDraft, workerJobWake: checked })
                      }
                    />
                  </div>

                  <div className="flex items-center justify-between gap-3">
                    <span className="text-[13px]" style={{ color: "var(--pi-ink)" }}>
                      {t("super.limitCompactionOverride")}
                    </span>
                    <Switch
                      checked={settingsDraft.workerCompactionOverrideEnabled}
                      onCheckedChange={(checked) =>
                        setSettingsDraft({ ...settingsDraft, workerCompactionOverrideEnabled: checked })
                      }
                    />
                  </div>

                  {settingsDraft.workerCompactionOverrideEnabled && (
                    <div className="space-y-1">
                      <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                        {t("super.limitCompactionPct")}
                      </label>
                      <Input
                        type="number"
                        min={50}
                        max={95}
                        value={settingsDraft.workerCompactionTriggerPercent}
                        onChange={(e) =>
                          setSettingsDraft({
                            ...settingsDraft,
                            workerCompactionTriggerPercent: Number(e.target.value) || 75,
                          })
                        }
                      />
                    </div>
                  )}
                </div>
              )}

              <div className="flex items-center justify-between pt-2">
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busy}
                  onClick={() => setConfirmResetDefaults(true)}
                >
                  {t("super.teamResetDefaults")}
                </Button>
                <Button
                  variant="primary"
                  size="sm"
                  disabled={busy}
                  onClick={() => {
                    if (!client) return;
                    setBusy(true);
                    setError(null);
                    void updateSuperSettings(client, settingsDraft)
                      .then(() => showSaved())
                      .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                      .finally(() => setBusy(false));
                  }}
                >
                  {savedNotice ? t("bot.saved") : t("common.save")}
                </Button>
              </div>
            </>
          ) : (
            <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
              {t("common.loading")}
            </div>
          )}
        </TabsContent>
      </Tabs>

      {error && (
        <div className="mt-3">
          <NoticeBar tone="danger" message={error} />
        </div>
      )}

      <ConfirmDialog
        open={confirmResetDefaults}
        onOpenChange={setConfirmResetDefaults}
        title={t("super.teamResetDefaults")}
        description={t("super.resetDefaultsConfirm")}
        confirmLabel={t("super.teamResetDefaults")}
        cancelLabel={t("common.cancel")}
        danger={false}
        onConfirm={() => {
          setConfirmResetDefaults(false);
          setSettingsDraft({ ...DEFAULT_SUPER_SETTINGS });
          if (!client) return;
          setBusy(true);
          setError(null);
          void updateSuperSettings(client, DEFAULT_SUPER_SETTINGS)
            .then(() => showSaved())
            .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
            .finally(() => setBusy(false));
        }}
      />
    </Dialog>
  );
}
