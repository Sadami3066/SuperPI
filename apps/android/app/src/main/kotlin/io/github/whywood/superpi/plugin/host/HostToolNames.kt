package io.github.whywood.superpi.plugin.host

internal object HostToolNames {
    private val engine = setOf("read", "write", "edit", "bash", "ls", "grep", "find")

    private val hostTools = setOf(
        "ask_user_question",
        "present",
        "system_shell",
        "goal",
        "job",
        "timer",
        "miniapp_create",
        "miniapp_load",
        "miniapp_list",
        "miniapp_delete",
        "miniapp_data",
        "todo_write",
        "memory_search",
        "memory_inspect_entity",
        "memory_read_archive",
        "memory_dispatch",
        "web_search",
        "web_fetch",
        "calendar",
        "virtual_screen",
        "screen_status",
        "screen_observe",
        "screen_screenshot",
        "screen_tap",
        "screen_swipe",
        "screen_scroll",
        "screen_type",
        "screen_key",
        "screen_wait",
        "screen_list_apps",
        "screen_launch",
        "screen_batch",
        "worker",
    )

    private val sdkTools = setOf(
        "tool_search",
        "tool_execute",
        "list_mcp_resources",
        "list_mcp_resource_templates",
        "read_mcp_resource",
    )

    private val reservedPrefixes = setOf("mcp__")

    val names: Set<String> = engine + hostTools + sdkTools

    fun isReserved(name: String): Boolean = name in names || reservedPrefixes.any(name::startsWith)

    fun conflicts(candidates: Iterable<String>): Set<String> =
        candidates.filterTo(sortedSetOf(), ::isReserved)
}
