package io.github.whywood.superpi.runtime

data class StorageMountInfo(
    val name: String,
    val description: String,
    val path: String,
)

internal fun buildPathsSection(
    skillsDir: String,
    appDataDir: String,
    sharedStorage: Boolean,
    mounts: List<StorageMountInfo>,
    readOnly: Boolean = false,
): String = buildString {
    if (!readOnly) {
        appendLine("Skills: $skillsDir.")
        appendLine("App data: $appDataDir. Bash and file tools use these real paths.")
    } else {
        appendLine("App data: $appDataDir. Read-only query tools (read, ls, grep, find) use explicit absolute paths.")
    }
    if (sharedStorage) {
        if (readOnly) {
            appendLine("Shared storage: $PUBLIC_STORAGE_ROOT — user files are readable.")
        } else {
            appendLine(
                "Shared storage: $PUBLIC_STORAGE_ROOT — all user files are readable and writable. " +
                    "Do not delete or overwrite user files unless the user asks.",
            )
        }
    }
    if (mounts.isNotEmpty()) {
        appendLine("Mounted directories:")
        mounts.forEach { mount ->
            append("- ")
            append(mount.name)
            append(" (")
            append(mount.path)
            append(")")
            val description = mount.description.trim()
            if (description.isNotEmpty()) {
                append(": ")
                append(description)
            }
            appendLine()
        }
    }
}.trim()

internal const val PUBLIC_STORAGE_ROOT = "/storage/emulated/0"
