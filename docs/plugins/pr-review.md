# Marketplace entry review checklist

Marketplace maintainers review every entry against this checklist and send back failed items with the reason.

## Entry

- [ ] `id` matches `plugin.json` inside the artifact and is not already owned by another publisher
- [ ] `version` matches the artifact; a listing under the same id must be higher than the published version
- [ ] `name` / `summary` / `description` / `tags` are bilingual, with no developer notes, internal links or exaggerated claims
- [ ] `artifact.url` stays publicly downloadable long term, and `artifact.sha256` / `artifact.size` match the file byte for byte
- [ ] The icon is a PNG of ≤128 KB; brand icons credit their source
- [ ] `plugin.requiredAccess` truthfully states the access tier the plugin needs (`normal`/`adb`/`root`), and plugins that need Root explain why
- [ ] `plugin.previews` (if present) are 1–6 HTTPS portrait images (9:20) that match the plugin

## Artifact

- [ ] The `.ddp` passes signature verification: `keyId` in `META-INF/plugin-signature.json` matches the entry's `publisherKeyId`
- [ ] The first listing records the publisher `keyId`; updates under the same id reuse the same key, and a new key means a new publisher
- [ ] `capabilities` / `provides` in `plugin.json` match actual behavior, with no undeclared capabilities
- [ ] `apiLevel` is 7, `ui.apiLevel` is 3, and `minHostVersion` is `2.0.0` or the lowest host that truly supports every used API
- [ ] No malicious, destructive or undisclosed behavior: no stealing credentials, chats or files, no bypassing system permissions
- [ ] Within size limits: 50 MB per plugin, 20 MB per skill

## Changes

- [ ] Update entries include change notes (highlights, behavior changes, migration notes where needed)
- [ ] Publisher takedown requests state a reason; security incidents are removed under the policy with an audit record kept
