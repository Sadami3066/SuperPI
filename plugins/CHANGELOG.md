# Changelog

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
