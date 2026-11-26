package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.coding.prompt.Skill
import io.github.whywood.superpi.sdk.coding.prompt.loadSkills
import io.github.whywood.superpi.sdk.coding.prompt.renderSkillPromptBlock
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

data class SkillExpansion(val name: String, val prompt: String)

data class SkillSummary(
    val name: String,
    val description: String,
)

interface SkillExpander {
    suspend fun expand(cwd: String, name: String, args: String): SkillExpansion?

    suspend fun list(cwd: String): List<SkillSummary>
}

class SkillCatalog(
    private val agentDir: File,
    private val extraSkillDirs: List<SkillSourceDir> = emptyList(),
) : SkillExpander {
    override suspend fun expand(cwd: String, name: String, args: String): SkillExpansion? {
        val skill = discover(cwd).firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        val content = runCatching {
            FileSystem.SYSTEM.read(skill.filePath.toPath()) { readUtf8() }
        }.getOrNull() ?: skill.content
        val fresh = skill.copy(content = content)
        val arguments = args.trim().takeIf { it.isNotEmpty() }
        return SkillExpansion(fresh.name, renderSkillPromptBlock(fresh, arguments))
    }

    override suspend fun list(cwd: String): List<SkillSummary> =
        discover(cwd)
            .map { skill -> SkillSummary(skill.name, skill.description) }
            .sortedBy { it.name.lowercase() }

    suspend fun listDiscovered(cwd: String): List<DiscoveredSkill> = runCatching {
        discoverMergedSkills(
            cwd = File(cwd),
            agentDir = agentDir,
            extraGlobalDirs = extraSkillDirs,
        )
    }.getOrDefault(emptyList())

    suspend fun findDiscovered(cwd: String, name: String): DiscoveredSkill? =
        listDiscovered(cwd).firstOrNull { it.skill.name.equals(name, ignoreCase = true) }

    private suspend fun discover(cwd: String): List<Skill> {
        val merged = listDiscovered(cwd).map { it.skill }
        if (merged.isNotEmpty()) return merged
        return runCatching {
            loadSkills(
                cwd = File(cwd).absolutePath.toPath(),
                agentDir = agentDir.absolutePath.toPath(),
                fs = FileSystem.SYSTEM,
                projectTrusted = true,
            )
        }.getOrDefault(emptyList())
    }
}
