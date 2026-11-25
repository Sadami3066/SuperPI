# Changelog

## 2026-11-25

- feat: the 2.0.3 plugin batch aligns the official and personal plugins on the SuperPI contract line (API level 7, `minHostVersion` 2.0.0; SDK release `plugin-api` 2.0.2 / `design` 1.1.0, while the modules still compile against `plugin-api` 2.0.1 / `design` 1.0.0): Feishu 2.7.2, WeCom 2.6.3, Xiaomi Cloud 2.7.5, Mail 1.6.3, DiDi Ride 1.3.2, RollingGo Hotels 1.4.2, Amap 1.5.4, 12306 1.4.0, Themes 1.0.1, MCP UI Demo 0.1.1; personal plugins SYSU 2.7.4, WeChat 0.5.3 and Zanao 1.0.19 [release] [versions]
- feat: plugin copy and cards converge on no filler, readable output and one-tap retry; dead compatibility branches and internal jargon are removed, and every failure states what happened next [ui] [plugin]
- feat: Feishu/WeCom install checks and log redaction, Xiaomi Cloud parameter hints, Amap/DiDi "open the official page" prompts on failure, SYSU per-site login state, and Zanao list incremental refresh [plugin]
- feat: WeChat and Zanao gain log diagnostics, while the CLI plugins share the same update/install and error-mapping path [plugin] [tooling]
- docs: plugin READMEs, quick start, manifest, runtime API and UI docs align on `plugin-api 2.0.2` / `design 1.1.0`, API level 7, `ui.apiLevel` 3, 39 capabilities and the current 36 host service accessors [docs]

## 2026-11-13

- docs: the plugin development guide and both READMEs now cover `plugin-api 1.1.0` / `design 0.7.0` (an addition within API level 6, host 1.0.6+): 38 capabilities, the `permissions`/`components` manifest fields, 18 new host services (settings, device, clipboard, permissions, app management, elevated execution, intents and dynamic broadcasts, standalone windows and foreground services, background tasks, location, contacts, SMS, calendar, media, speech, web, host credentials, accessibility), notification listening and actions, system event hooks, page arguments and interactive pickers, design 0.7.0 components, and the 1.0.x → 1.1.0 migration notes [docs] [capabilities]

## 2026-11-12

- chore: bump the template, sample and documentation pins to `plugin-api:2.0.0` (patch release, same API level) [dependency] [template]
- feat: migrate the template and sample to `plugin-api:1.0.0` (API level 6, `ui.apiLevel` 2); manifests now use `apiLevel` 6 / `minHostVersion` 1.0.4 and the top-level factories and interfaces; both READMEs updated their SDK version tables [dependency] [template] [breaking]
- feat: add the `sample/` test fixture (`sample_echo` tool plus the `sample.main` page, with a development-only Ed25519 signing key committed with the repository) so the host repository's `scripts/plugin-e2e.sh` can regress external plugin install, enable, disable, upgrade and uninstall on a device [tooling] [template]
- chore: bump the template to `plugin-api:0.8.1` (complete consumer R8 keep rules) and set the template manifest `minHostVersion` to `1.0.1`; READMEs and the development guide version tables updated [dependency] [docs]

## 2026-11-10

- feat(api): plugin API v2 (`PLUGIN_API_LEVEL` / `BASELINE_API_LEVEL` 4→5); removed `host.privilege()` / `PrivilegeService` and the `requiresPrivilege` manifest field; `host.linux()` / `host.files()` keep their semantics, and the execution identity is set by the user's access tier (LOCAL/ADB/ROOT) [api] [breaking]
- docs: updated the manifest fields, capabilities list, host services, ABI and security model in `docs/PLUGIN_DEV.md`; the template `plugin.json` `apiLevel` is now 5; READMEs updated for the API level and privilege changes [docs] [template]

## 2026-11-09

- refactor: move the official plugins to their own repository [SuperPI-Official-Plugins](https://github.com/Sadami3066/SuperPI-Official-Plugins) (feishu 1.0.1 / wecom 1.0.1 / sysu 1.0.0 / micloud 1.0.2); `jwxt` renamed to `sysu` everywhere (directory, Kotlin packages and classes, plugin id `io.github.whywood.superpi.plugin.sysu`); this repository keeps the template, the development guide and the listing rules [split] [breaking]

- feat(jwxt): the plugin is now called "SYSU CLI" externally and bundles the multi-site `sysu` CLI (academic affairs / online teaching / sports venues / campus network fees / SSO), with the skill id changed to `superpi-sysu`; install paths move to `/root/projects/sysu` and `/usr/local/bin/sysu`, and cleanup still removes the old `jwxt` paths [jwxt] [breaking]

- feat: add the public plugin template `template/`: a standalone Gradle project resolving `plugin-api:0.7.0` from public Maven, with `assemblePlugin` / `signPlugin` / `verifyPlugin` and the Ed25519 signing implementation, plus an example tool, skill and prompt section; local build, signing and verification all pass [tooling] [template]
- docs: add the public `docs/PLUGIN_DEV.md` (contract, manifest, `.ddp` format, signing algorithm, lifecycle, tools/skills/UI/services, ABI and security model); add `MARKET_POLICY.md` and the `PR_REVIEW` listing rules; READMEs now use the public Maven setup [docs]
- chore: switch dependency coordinates to `io.github.sadami3066.superpi` (`plugin-api:0.7.0`, `design:0.6.0`) and resolve from `https://sadami3066.github.io/SuperPI-Maven`; the four plugin artifacts keep their hashes, so marketplace entries need no republish [build]
- docs: replace the mavenLocal publishing notes with the public Maven release flow in the plugin and interface AGENTS files [docs]

## Earlier versions

Earlier version history for the plugins (Feishu, WeCom, SYSU academic affairs, Mi Cloud) lives in each plugin directory and in the marketplace registry; this file records changes from when the public repository was enabled.
