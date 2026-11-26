package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.coding.prompt.Frontmatter
import io.github.whywood.superpi.sdk.coding.prompt.Skill
import java.io.File

data class SkillSourceDir(
    val dir: File,
    val sourceLabel: String,
)

data class DiscoveredSkill(
    val skill: Skill,
    val sourceLabel: String,
    val managed: Boolean,
)

/**
 * Standard global skill directories shared across modern coding harnesses:
 * - `~/.agents/skills` (Agent Skills open standard: Codex, Pi, OpenCode, Cursor, Gemini CLI)
 * - `~/.codex/skills` (OpenAI Codex)
 * - `~/.claude/skills` (Claude Code)
 * - `~/.pi/agent/skills` (Pi coding agent)
 */
fun defaultExternalSkillDirs(
    userHome: File = File(System.getProperty("user.home").orEmpty()),
): List<SkillSourceDir> {
    if (userHome.path.isBlank() || !userHome.isDirectory) return emptyList()
    if (System.getenv("SUPER_PI_DISABLE_EXTERNAL_SKILLS") == "1") return emptyList()
    return listOf(
        SkillSourceDir(File(userHome, ".agents/skills"), "~/.agents/skills"),
        SkillSourceDir(File(userHome, ".codex/skills"), "~/.codex/skills"),
        SkillSourceDir(File(userHome, ".claude/skills"), "~/.claude/skills"),
        SkillSourceDir(File(userHome, ".pi/agent/skills"), "~/.pi/agent/skills"),
    )
}

/**
 * Discovers skills across project roots (`.pi/skills`, `.agents/skills`, `.claude/skills`),
 * Super PI's native global directory (`<agentDir>/skills`), and external cross-harness
 * global directories. Earlier sources win on duplicate skill names.
 */
fun discoverMergedSkills(
    cwd: File,
    agentDir: File,
    extraGlobalDirs: List<SkillSourceDir> = emptyList(),
): List<DiscoveredSkill> {
    val nativeGlobalDir = File(agentDir, "skills")
    val nativeCanonical = runCatching { nativeGlobalDir.canonicalFile }.getOrDefault(nativeGlobalDir)

    val orderedRoots = buildList {
        if (cwd.path.isNotBlank() && cwd.isDirectory) {
            add(Triple(File(cwd, ".pi/skills"), ".pi/skills", false))
            add(Triple(File(cwd, ".agents/skills"), ".agents/skills", false))
            add(Triple(File(cwd, ".claude/skills"), ".claude/skills", false))
        }
        add(Triple(nativeGlobalDir, "super-pi", true))
        extraGlobalDirs.forEach { src ->
            val canon = runCatching { src.dir.canonicalFile }.getOrDefault(src.dir)
            if (canon != nativeCanonical) {
                add(Triple(src.dir, src.sourceLabel, false))
            }
        }
    }

    val seenNames = HashSet<String>()
    val seenFiles = HashSet<String>()
    val result = mutableListOf<DiscoveredSkill>()

    for ((root, label, managed) in orderedRoots) {
        if (!root.isDirectory) continue
        val children = root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        for (dir in children) {
            val skillFile = File(dir, "SKILL.md")
            if (!skillFile.isFile) continue
            val canonicalFile = runCatching { skillFile.canonicalPath }.getOrDefault(skillFile.absolutePath)
            if (!seenFiles.add(canonicalFile)) continue
            val parsed = parseDiscoveredSkillFile(skillFile, dir) ?: continue
            val key = parsed.name.lowercase()
            if (!seenNames.add(key)) continue
            result += DiscoveredSkill(
                skill = parsed,
                sourceLabel = label,
                managed = managed,
            )
        }
    }

    return result
}

private fun parseDiscoveredSkillFile(skillFile: File, baseDir: File): Skill? {
    val raw = runCatching { skillFile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
    val parsed = Frontmatter.parse(raw)
    val fields = parsed.frontmatter
    val name = fields["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: baseDir.name.trim()
    if (name.isEmpty()) return null
    val description = fields["description"]?.trim()?.takeIf { it.isNotEmpty() }
        ?: parsed.body
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
            ?.take(140)
        ?: name
    val disableModelInvocation = fields["disable-model-invocation"]
        ?.trim()
        ?.equals("true", ignoreCase = true) == true
    return Skill(
        name = name,
        description = description,
        filePath = skillFile.absolutePath,
        content = parsed.body.ifBlank { raw },
        baseDir = baseDir.absolutePath,
        disableModelInvocation = disableModelInvocation,
    )
}
