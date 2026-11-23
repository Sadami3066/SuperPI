---
name: template-skill
description: Example skill bundled with the plugin template. It shows how an external plugin hands markdown and supporting files to the agent as a skill; use it when the user asks how the template skill works or when a skill-loading demo is needed.
---

# Plugin template skill

This example skill ships with the `plugin-template` project and shows how an external plugin provides skills:

1. `assets/skills/template-skill/SKILL.md` is the source of this file; the plugin reads it from `host.assetsDir` in `skills(host)` and returns it as `PluginSkill.markdown`.
2. `assets/skills/template-skill/references/format.md` is a supporting file, declared through `PluginSkillFile(assetPath = ...)` and materialized into the same skill directory as SKILL.md.
3. The host writes skills into the agent's skill directory only while the plugin is in a contributing phase (`ACTIVE` / `DEGRADED` / `PENDING_RESTART`); disabling or uninstalling the plugin cleans them up by ownership.

## When to use

- When the user is developing a Super PI Agent plugin and needs to see the skill directory layout, load this skill as a minimal example.
- Use it to demonstrate "skill + supporting reference file"; do not treat it as a real business capability.

## Requirements

- Skill markdown must have YAML frontmatter (`name`, `description`), and `name` must match `PluginSkill.id`.
- Supporting file paths must be relative and must not contain `..`, backslashes or absolute paths; provide either `content` or `assetPath`.
