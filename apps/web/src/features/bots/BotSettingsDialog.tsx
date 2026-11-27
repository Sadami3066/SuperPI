import { useEffect, useMemo, useRef, useState } from "react";
import type { HostBotDto, HostBotPolicyDto, PermissionMode } from "../../api/types";
import { useI18n } from "../../i18n";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import {
  Button,
  ConfirmDialog,
  Dialog,
  Input,
  NoticeBar,
  Select,
  Switch,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  Textarea,
} from "../../ui";
import { BotAvatar } from "../shared/BrandAssets";

async function resizeImageToDataUrl(file: File, maxDim = 256): Promise<string | null> {
  if (!file.type.startsWith("image/")) return null;
  const srcUrl = await new Promise<string>((resolve) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ""));
    reader.onerror = () => resolve("");
    reader.readAsDataURL(file);
  });
  if (!srcUrl) return null;
  const img = await new Promise<HTMLImageElement | null>((resolve) => {
    const el = new Image();
    el.onload = () => resolve(el);
    el.onerror = () => resolve(null);
    el.src = srcUrl;
  });
  if (!img || !img.width || !img.height) return null;
  const scale = Math.min(1, maxDim / Math.max(img.width, img.height));
  const w = Math.max(1, Math.round(img.width * scale));
  const h = Math.max(1, Math.round(img.height * scale));
  const canvas = document.createElement("canvas");
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext("2d");
  if (!ctx) return null;
  ctx.drawImage(img, 0, 0, w, h);
  return canvas.toDataURL("image/png");
}

export function BotSettingsDialog({
  bot,
  onClose,
}: {
  bot: HostBotDto | null;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const dark = useSettingsStore((s) => s.dark);
  const providers = useSettingsStore((s) => s.providers);
  const loadBotDetails = useBotStore((s) => s.loadBotDetails);
  const updateBotProfile = useBotStore((s) => s.updateBotProfile);
  const saveAgentsDoc = useBotStore((s) => s.saveAgentsDoc);
  const updateBotPolicy = useBotStore((s) => s.updateBotPolicy);
  const setPermissionMode = useBotStore((s) => s.setPermissionMode);
  const deleteBot = useBotStore((s) => s.deleteBot);

  const [tab, setTab] = useState("identity");
  const [name, setName] = useState("");
  const [intro, setIntro] = useState("");
  const [avatarDraft, setAvatarDraft] = useState<string>("");
  const [clearAvatarFlag, setClearAvatarFlag] = useState(false);
  const [defaultModel, setDefaultModel] = useState("");
  const avatarInputRef = useRef<HTMLInputElement | null>(null);
  const [permissionMode, setPermMode] = useState<PermissionMode>("workspace_auto");
  const [agentsDoc, setAgentsDoc] = useState("");
  const [policy, setPolicy] = useState<HostBotPolicyDto | null>(null);
  const [detailsStatus, setDetailsStatus] = useState<"loading" | "ready" | "error">("loading");
  const [busy, setBusy] = useState(false);
  const [savedFlag, setSavedFlag] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const savedTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (savedTimerRef.current !== null) clearTimeout(savedTimerRef.current);
    };
  }, []);

  const fetchDetails = (targetBot: HostBotDto) => {
    if (!client) return;
    setDetailsStatus("loading");
    void loadBotDetails(client, targetBot.id)
      .then((res) => {
        setAgentsDoc(res.doc.content ?? "");
        setPolicy(res.policy);
        setDetailsStatus("ready");
      })
      .catch(() => {
        setDetailsStatus("error");
      });
  };

  useEffect(() => {
    if (!bot || !client) return;
    setTab("identity");
    setName(bot.name);
    setIntro(bot.intro ?? "");
    setAvatarDraft(bot.avatar ?? "");
    setClearAvatarFlag(false);
    setDefaultModel(bot.defaultModel ?? "");
    setPermMode((bot.permissionMode as PermissionMode) || "workspace_auto");
    setSavedFlag(false);
    setSaveError(null);
    setPolicy(null);
    fetchDetails(bot);
  }, [bot, client, loadBotDetails]);

  const modelOptions = useMemo(() => {
    const opts = [{ value: "__inherit__", label: t("bot.modelInherit") }];
    for (const p of providers.filter((x) => x.configured)) {
      for (const m of p.models) {
        opts.push({ value: `${p.id}/${m.id}`, label: `${p.name} · ${m.displayName || m.id}` });
      }
    }
    return opts;
  }, [providers, t]);

  const permissionOptions = useMemo(
    () => [
      { value: "read_only", label: t("perm.read_only") },
      { value: "ask_commands", label: t("perm.ask_commands") },
      { value: "workspace_auto", label: t("perm.workspace_auto") },
      { value: "full_auto", label: t("perm.full_auto") },
    ],
    [t],
  );

  const scopeOptions = useMemo(
    () => [
      { value: "personal", label: t("bot.scopePersonal") },
      { value: "work_only", label: t("bot.scopeWorkOnly") },
      { value: "off", label: t("bot.scopeOff") },
    ],
    [t],
  );

  if (!bot) return null;

  const handleSave = async () => {
    if (!client || detailsStatus !== "ready") return;
    setBusy(true);
    setSaveError(null);
    try {
      await updateBotProfile(client, bot.id, {
        name: name.trim() || bot.name,
        intro: intro.trim(),
        clearIntro: !intro.trim(),
        ...(avatarDraft && avatarDraft !== (bot.avatar ?? "") ? { avatar: avatarDraft } : {}),
        ...(clearAvatarFlag ? { clearAvatar: true } : {}),
        defaultModel: defaultModel || undefined,
        updateDefaultModel: !bot.isRemote,
      });
      if (!bot.isRemote) {
        await setPermissionMode(client, bot.id, permissionMode);
      }
      await saveAgentsDoc(client, bot.id, agentsDoc);
      if (policy) {
        await updateBotPolicy(client, bot.id, {
          allCapabilities: bot.isHome ? true : policy.allCapabilities,
          skills: policy.skills,
          mcpServers: policy.mcpServers,
          memoryScope: policy.memoryScope,
          memoryDispatch: policy.memoryDispatch,
          superEnabled: policy.superEnabled,
        });
      }
      setSavedFlag(true);
      if (savedTimerRef.current !== null) clearTimeout(savedTimerRef.current);
      savedTimerRef.current = setTimeout(() => setSavedFlag(false), 1600);
    } catch (err) {
      setSaveError(err instanceof Error ? err.message : "Failed to save settings");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Dialog
        open={Boolean(bot)}
        onOpenChange={(v) => {
          if (!v) onClose();
        }}
        title={bot.name}
        maxWidthClass="max-w-[560px]"
      >
        <Tabs value={tab} onValueChange={setTab} className="space-y-4">
          <TabsList>
            <TabsTrigger value="identity">{t("bot.tabIdentity")}</TabsTrigger>
            <TabsTrigger value="instructions">{t("bot.tabInstructions")}</TabsTrigger>
            <TabsTrigger value="capabilities">{t("bot.tabCapabilities")}</TabsTrigger>
          </TabsList>

          <TabsContent value="identity" className="space-y-3.5">
            {!bot.isRemote && (
              <div className="flex items-center gap-3.5 pb-1">
                <BotAvatar
                  avatar={clearAvatarFlag ? null : avatarDraft || bot.avatar}
                  name={name || bot.name}
                  isRemote={bot.isRemote}
                  dark={dark}
                  size={52}
                />
                <div className="space-y-1.5">
                  <div className="text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                    {t("bot.avatarLabel")}
                  </div>
                  <div className="flex items-center gap-2">
                    <input
                      ref={avatarInputRef}
                      type="file"
                      accept="image/png,image/jpeg,image/webp,image/gif"
                      className="hidden"
                      onChange={async (e) => {
                        const file = e.target.files?.[0];
                        e.target.value = "";
                        if (!file) return;
                        const resized = await resizeImageToDataUrl(file);
                        if (resized) {
                          setAvatarDraft(resized);
                          setClearAvatarFlag(false);
                        }
                      }}
                    />
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => avatarInputRef.current?.click()}
                    >
                      {t("bot.avatarUpload")}
                    </Button>
                    {(avatarDraft || bot.avatar) && !clearAvatarFlag && (
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => {
                          setAvatarDraft("");
                          setClearAvatarFlag(true);
                        }}
                      >
                        {t("bot.avatarReset")}
                      </Button>
                    )}
                  </div>
                </div>
              </div>
            )}

            <div className="space-y-1">
              <label className="block text-[12.5px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                {t("bot.nameLabel")}
              </label>
              <Input value={name} onChange={(e) => setName(e.target.value)} />
            </div>

            <div className="space-y-1">
              <label className="block text-[12.5px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                {t("bot.introLabel")}
              </label>
              <Input value={intro} onChange={(e) => setIntro(e.target.value)} />
            </div>

            {!bot.isRemote && (
              <>
                <div className="space-y-1">
                  <label className="block text-[12.5px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                    {t("wb.model")}
                  </label>
                  <Select
                    value={defaultModel || "__inherit__"}
                    onValueChange={(v) => setDefaultModel(v === "__inherit__" ? "" : v)}
                    options={modelOptions}
                    className="w-full"
                  />
                </div>

                <div className="space-y-1">
                  <label className="block text-[12.5px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                    {t("wb.permission")}
                  </label>
                  <Select
                    value={permissionMode}
                    onValueChange={(v) => setPermMode(v as PermissionMode)}
                    options={permissionOptions}
                    className="w-full"
                  />
                </div>
              </>
            )}

            {!bot.isHome && (
              <div className="pt-2">
                <Button variant="danger" size="sm" onClick={() => setConfirmDelete(true)}>
                  {t("bot.deleteBot")}
                </Button>
              </div>
            )}
          </TabsContent>

          <TabsContent value="instructions" className="space-y-2">
            {detailsStatus === "error" ? (
              <NoticeBar
                tone="danger"
                message={t("bot.detailsLoadFailed")}
                action={
                  <Button variant="secondary" size="sm" onClick={() => fetchDetails(bot)}>
                    {t("conn.retry")}
                  </Button>
                }
              />
            ) : detailsStatus === "loading" ? (
              <div className="py-6 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("common.loading")}
              </div>
            ) : (
              <Textarea
                rows={10}
                value={agentsDoc}
                onChange={(e) => setAgentsDoc(e.target.value)}
                placeholder={t("bot.instructionsPlaceholder")}
                style={{ fontFamily: "var(--pi-font-mono)" }}
              />
            )}
          </TabsContent>

          <TabsContent value="capabilities" className="space-y-4">
            {policy ? (
              <>
                <div className="space-y-1.5">
                  <label className="block text-[12.5px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                    {t("bot.memoryScope")}
                  </label>
                  <Select
                    value={policy.memoryScope}
                    onValueChange={(v) => setPolicy({ ...policy, memoryScope: v })}
                    options={scopeOptions}
                    className="w-full"
                  />
                </div>

                <div className="flex items-center justify-between gap-3">
                  <span className="text-[13.5px]" style={{ color: "var(--pi-ink)" }}>
                    {t("bot.memoryDispatch")}
                  </span>
                  <Switch
                    checked={policy.memoryDispatch}
                    disabled={policy.memoryScope === "off"}
                    onCheckedChange={(checked) => setPolicy({ ...policy, memoryDispatch: checked })}
                  />
                </div>

                <div className="flex items-center justify-between gap-3">
                  <span className="text-[13.5px]" style={{ color: "var(--pi-ink)" }}>
                    {t("bot.superEnabled")}
                  </span>
                  <Switch
                    checked={policy.superEnabled}
                    onCheckedChange={(checked) => setPolicy({ ...policy, superEnabled: checked })}
                  />
                </div>

                <div className="flex items-center justify-between gap-3">
                  <span className="text-[13.5px]" style={{ color: "var(--pi-ink)" }}>
                    {t("bot.allCapabilities")}
                  </span>
                  <Switch
                    checked={bot.isHome ? true : policy.allCapabilities}
                    disabled={bot.isHome}
                    onCheckedChange={(checked) => setPolicy({ ...policy, allCapabilities: checked })}
                  />
                </div>

                {!bot.isHome && !policy.allCapabilities && (
                  <div className="space-y-3 pt-1">
                    {policy.availableSkills.length > 0 && (
                      <div className="space-y-1.5">
                        <div className="text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                          {t("bot.allowedSkills")}
                        </div>
                        <div className="flex flex-wrap gap-1.5">
                          {policy.availableSkills.map((sk) => {
                            const selected = policy.skills.includes(sk);
                            return (
                              <button
                                key={sk}
                                type="button"
                                onClick={() => {
                                  const next = selected
                                    ? policy.skills.filter((s) => s !== sk)
                                    : [...policy.skills, sk];
                                  setPolicy({ ...policy, skills: next });
                                }}
                                className="rounded-full px-2.5 py-1 text-[12px] transition-colors"
                                style={{
                                  background: selected ? "var(--pi-accent-soft)" : "var(--pi-paper)",
                                  color: selected ? "var(--pi-accent)" : "var(--pi-ink-secondary)",
                                  border: "0.5px solid var(--pi-hairline)",
                                }}
                              >
                                {sk}
                              </button>
                            );
                          })}
                        </div>
                      </div>
                    )}

                    {policy.availableMcpServers.length > 0 && (
                      <div className="space-y-1.5">
                        <div className="text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                          {t("bot.allowedMcp")}
                        </div>
                        <div className="flex flex-wrap gap-1.5">
                          {policy.availableMcpServers.map((srv) => {
                            const selected = policy.mcpServers.includes(srv);
                            return (
                              <button
                                key={srv}
                                type="button"
                                onClick={() => {
                                  const next = selected
                                    ? policy.mcpServers.filter((s) => s !== srv)
                                    : [...policy.mcpServers, srv];
                                  setPolicy({ ...policy, mcpServers: next });
                                }}
                                className="rounded-full px-2.5 py-1 text-[12px] transition-colors"
                                style={{
                                  background: selected ? "var(--pi-accent-soft)" : "var(--pi-paper)",
                                  color: selected ? "var(--pi-accent)" : "var(--pi-ink-secondary)",
                                  border: "0.5px solid var(--pi-hairline)",
                                }}
                              >
                                {srv}
                              </button>
                            );
                          })}
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </>
            ) : detailsStatus === "error" ? (
              <NoticeBar
                tone="danger"
                message={t("bot.detailsLoadFailed")}
                action={
                  <Button variant="secondary" size="sm" onClick={() => fetchDetails(bot)}>
                    {t("conn.retry")}
                  </Button>
                }
              />
            ) : (
              <div className="py-6 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("common.loading")}
              </div>
            )}
          </TabsContent>
        </Tabs>

        {detailsStatus === "error" && tab === "identity" && (
          <div className="mt-3">
            <NoticeBar
              tone="danger"
              message={t("bot.detailsLoadFailed")}
              action={
                <Button variant="secondary" size="sm" onClick={() => fetchDetails(bot)}>
                  {t("conn.retry")}
                </Button>
              }
            />
          </div>
        )}

        {saveError && (
          <div className="mt-3 text-[12.5px]" style={{ color: "var(--pi-danger)" }}>
            {saveError}
          </div>
        )}

        <div className="mt-5 flex items-center justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t("common.close")}
          </Button>
          <Button
            variant="primary"
            disabled={busy || detailsStatus !== "ready"}
            onClick={() => void handleSave()}
          >
            {savedFlag ? t("bot.saved") : t("bot.save")}
          </Button>
        </div>
      </Dialog>

      <ConfirmDialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        title={t("bot.deleteBot")}
        description={t("bot.deleteBotDesc")}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (!client) return;
          void deleteBot(client, bot.id)
            .then(async () => {
              const nextBots = useBotStore.getState().bots;
              await useSessionStore.getState().refreshSessionLists(client, nextBots);
              setConfirmDelete(false);
              onClose();
            })
            .catch((err) => {
              setSaveError(err instanceof Error ? err.message : "Failed to delete bot");
              setConfirmDelete(false);
            });
        }}
      />
    </>
  );
}
