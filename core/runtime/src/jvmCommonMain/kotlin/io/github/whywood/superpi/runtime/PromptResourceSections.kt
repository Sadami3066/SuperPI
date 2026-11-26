package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.coding.prompt.SystemPromptInput
import io.github.whywood.superpi.sdk.coding.prompt.buildSystemPrompt
import io.github.whywood.superpi.sdk.coding.prompt.loadPromptResources
import okio.FileSystem
import okio.Path

internal class PromptResourceSections(
    private val cwd: Path,
    private val agentDir: Path,
    private val fs: FileSystem,
    private val excludedSkills: Set<String>,
    private val selectedTools: List<String> = emptyList(),
    private val extraSkillDirs: List<SkillSourceDir> = emptyList(),
) {
    private val trackedKeys = setOf("skills", "project_context", "addendum")
    @Volatile
    private var baseKeys: Set<String>? = null

    suspend fun current(): Map<String, String> {
        val sections = rendered() ?: return emptyMap()
        val knownBaseKeys = baseKeys ?: sections.keys.intersect(trackedKeys).also { baseKeys = it }
        val result = LinkedHashMap<String, String>()
        for (key in trackedKeys) {
            val value = sections[key]
            when {
                value != null -> result[key] = value
                key in knownBaseKeys -> result[key] = ""
            }
        }
        return result
    }

    private suspend fun rendered(): Map<String, String>? = runCatching {
        val resources = loadPromptResources(
            cwd = cwd,
            agentDir = agentDir,
            fs = fs,
            projectTrusted = true,
            excludedSkills = excludedSkills,
        )
        val extraSkills = if (fs === FileSystem.SYSTEM) {
            discoverMergedSkills(
                cwd = cwd.toFile(),
                agentDir = agentDir.toFile(),
                extraGlobalDirs = extraSkillDirs,
            ).map { it.skill }.filterNot { it.name in excludedSkills }
        } else {
            emptyList()
        }
        val mergedSkills = buildList {
            val seen = HashSet<String>()
            for (skill in resources.skills) {
                if (seen.add(skill.name.lowercase())) add(skill)
            }
            for (skill in extraSkills) {
                if (seen.add(skill.name.lowercase())) add(skill)
            }
        }
        buildSystemPrompt(
            SystemPromptInput(
                preamble = resources.systemPromptOverride,
                addendum = resources.appendSystemPrompt,
                projectInstructions = resources.contextFiles,
                skills = mergedSkills,
                selectedTools = selectedTools,
                cwd = cwd,
            ),
        ).sections
    }.getOrNull()
}
