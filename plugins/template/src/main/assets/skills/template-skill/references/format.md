# Skill supporting file example

Initial version: `1.0.0`

This file is declared by `PluginSkillFile(path = "references/format.md", assetPath = "skills/template-skill/references/format.md")`,
and the host materializes `assets/skills/template-skill/references/format.md` as `references/format.md` in the skill directory.

Rules:

- `path` is a path relative to the skill directory.
- `assetPath` is a path relative to the plugin's `assets/`.
- A file is provided either by `content` (an inline string) or by `assetPath` (an asset file), never both.
