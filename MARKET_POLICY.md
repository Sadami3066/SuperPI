# Marketplace policy

The DeepDeep-Market repository is the official plugin marketplace: entries are reviewed by maintainers, the registry is signed with a maintainer key, and the app browses and installs through "Plugins → Market". This policy covers what may be listed, the review standard and the takedown process.

## What may be listed

- Plugins, skills and MCP connectors that add capability to Super PI Agent; package format and contract are described in `docs/PLUGIN_DEV.md`.
- Artifacts that can be downloaded publicly and stay available long term (direct GitHub Releases links preferred), with a `sha256` matching the published file.
- The publisher holds redistribution rights; the repository keeps a LICENSE or source link (redistribution-friendly licenses such as MIT, Apache-2.0 or CC-BY).

## What is prohibited

- Malicious or destructive behavior: stealing credentials, chats or files, undisclosed data exfiltration, destroying user data, bypassing system permissions or security mechanisms, silent installs, hidden behavior, privilege-escalation backdoors.
- Credential abuse: shipping other people's accounts, using stolen API keys, signing in to third-party accounts on a user's behalf, or collecting private data unrelated to the plugin's function.
- Serious mismatch with the description: advertised features that do not match the actual tools, skills or pages, or undeclared capabilities smuggled into the package.
- Infringement and violations: piracy, cracking, or content that breaks local law or platform terms; brand icons may only identify their source.

## Submission requirements

Open a PR against [DeepDeep-Market](https://github.com/Sadami3066/DeepDeep-Market) and fill in the entry following the repository template. Every entry must include:

- `id` (matching `plugin.json` inside the package) and `version` (semantic, matching the artifact).
- `name` / `summary` / `description` / `tags` in both Chinese and English, with no developer-facing notes, internal links or exaggerated claims.
- `artifact.url`, `artifact.sha256` and `artifact.size` matching the Release file byte for byte.
- `publisherKeyId` matching the `keyId` in the package's `META-INF/plugin-signature.json`; a new key means a new publisher.
- A PNG icon of ≤128 KB (brand icons must credit their source).

See the repository's `PR_REVIEW.md` for the review checklist and per-item notes. Size limits: 50 MB per plugin, 20 MB per skill; remote MCP accepts HTTP endpoints only.

## Signing and trust

- Plugin packages must be signed with an Ed25519 private key over the canonical entry digest; on first install the app pins the publisher `keyId` with TOFU.
- Updates must use the same publisher key; after a key change, an update under the same `id` is treated as a different publisher and automatic upgrades are refused.
- The registry is signed with a maintainer `ec256` key, and the app verifies the signature before showing entries.

## Takedown and retraction

- Maintainers may remove entries for security, infringement, breakage or policy violations. Installed plugins are never uninstalled remotely; the app only stops offering updates for that entry.
- Publishers requesting removal must state a reason; security incidents are removed from the registry with an audit record kept.
- A publisher that was trusted but turns destructive can have its `keyId` blacklisted by maintainers, and all later entries from it are refused.

## Responsibility

Publishers are responsible for their plugin's behavior, license and updates. The marketplace provides distribution and review, but plugins run in the same process and with the same permissions as the app, so installing and using them is at the user's and publisher's risk. The install page shows the publisher, signature and declared capabilities; only install plugins from sources you trust.
