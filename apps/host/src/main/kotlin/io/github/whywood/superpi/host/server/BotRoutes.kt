package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.data.AGENT_DOC_MAX_BYTES
import io.github.whywood.superpi.data.relay.RemoteBot
import io.github.whywood.superpi.data.relay.RemoteWorkspaces
import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.host.platform.HostSecretEnv
import io.github.whywood.superpi.remote.RemoteAdminResult
import io.github.whywood.superpi.remote.RemoteControlResult
import io.github.whywood.superpi.remote.RemoteSessionRegistry
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.io.File

internal fun Route.installBotRoutes(controller: SuperPiController) {
    get("/v1/bots") {
        val localBots = controller.botRegistry.listAllBots().map { ws ->
            toLocalBotDto(controller, ws)
        }
        val remoteBots = controller.remoteBots.value.map { rb ->
            toRemoteBotDto(controller, rb)
        }
        call.respond(localBots + remoteBots)
    }

    post("/v1/bots") {
        val req = call.receive<AddBotRequest>()
        val targetDeviceId = req.targetDeviceId?.trim()?.takeIf { it.isNotEmpty() }
        val localDeviceId = controller.relayStore.read()?.deviceId
        if (targetDeviceId != null && targetDeviceId != localDeviceId) {
            val cleanName = req.name?.trim().orEmpty()
            if (cleanName.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Bot name is required"))
                return@post
            }
            when (
                val res = controller.remoteBotAdminClient.createBot(
                    deviceId = targetDeviceId,
                    name = cleanName,
                    avatarDataUrl = req.avatar,
                    intro = req.intro,
                )
            ) {
                is RemoteAdminResult.Ok -> {
                    val dev = controller.relayLive.devices.value.firstOrNull { it.deviceId == targetDeviceId }
                    val createdId = "remote:$targetDeviceId:${res.value.id}"
                    call.respond(
                        HttpStatusCode.Created,
                        BotDto(
                            id = createdId,
                            name = res.value.name,
                            cwd = "",
                            isHome = res.value.id == "home",
                            avatar = res.value.avatar,
                            intro = req.intro?.trim()?.takeIf { it.isNotEmpty() },
                            isRemote = true,
                            deviceId = targetDeviceId,
                            deviceName = dev?.name ?: targetDeviceId,
                            remoteBotId = res.value.id,
                            online = dev?.online ?: true,
                            capabilities = dev?.capabilities.orEmpty(),
                        ),
                    )
                }
                is RemoteAdminResult.Error -> {
                    call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
                }
            }
            return@post
        }

        val rawModel = req.model?.trim()?.takeIf { it.isNotEmpty() }
        val parsedModel = if (rawModel != null) {
            val spec = parseControlModelSpec(rawModel) ?: run {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiErrorResponse("Invalid model format '$rawModel'. Expected provider/model."),
                )
                return@post
            }
            val thinking = parseOptionalThinking(req.thinkingLevel) ?: spec.thinkingLevel
            spec.copy(thinkingLevel = thinking)
        } else {
            null
        }
        val dirFile = req.dir?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
        try {
            val added = controller.botRegistry.addBot(
                directory = dirFile,
                name = req.name,
                defaultModel = parsedModel,
                intro = req.intro,
                avatar = req.avatar,
            )
            if (controller.isRelayLiveInitialized) controller.relayLive.refreshBots()
            runCatching { controller.superOrchestrator.refreshBlueprints() }
            call.respond(HttpStatusCode.Created, toLocalBotDto(controller, added))
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid request"))
        }
    }

    patch("/v1/bots/{id}") {
        val id = call.parameters["id"].orEmpty()
        val req = call.receive<UpdateBotRequest>()

        val remote = controller.findRemoteBotByWorkspaceId(id)
        if (remote != null) {
            when (
                val res = controller.remoteBotAdminClient.updateBot(
                    deviceId = remote.deviceId,
                    botId = remote.botId,
                    name = req.name,
                    avatarDataUrl = req.avatar,
                    clearAvatar = req.clearAvatar,
                    intro = req.intro,
                    clearIntro = req.clearIntro,
                )
            ) {
                is RemoteAdminResult.Ok -> {
                    call.respond(
                        toRemoteBotDto(
                            controller,
                            remote.copy(
                                botName = res.value.name,
                                avatar = if (req.clearAvatar) null else (res.value.avatar ?: remote.avatar),
                                intro = if (req.clearIntro) "" else (req.intro?.trim() ?: remote.intro),
                            ),
                        ),
                    )
                }
                is RemoteAdminResult.Error -> {
                    call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
                }
            }
            return@patch
        }

        val parsedModel: ModelConfig? = if (req.updateDefaultModel) {
            val rawModel = req.defaultModel?.trim()?.takeIf { it.isNotEmpty() }
            if (rawModel == null) {
                null
            } else {
                val spec = parseControlModelSpec(rawModel) ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiErrorResponse("Invalid model format '$rawModel'. Expected provider/model."),
                    )
                    return@patch
                }
                val thinking = parseOptionalThinking(req.defaultThinkingLevel) ?: spec.thinkingLevel
                spec.copy(thinkingLevel = thinking)
            }
        } else {
            null
        }
        try {
            val updated = controller.botRegistry.updateBot(
                idOrName = id,
                name = req.name,
                intro = req.intro,
                clearIntro = req.clearIntro,
                avatar = req.avatar,
                clearAvatar = req.clearAvatar,
                defaultModel = parsedModel,
                updateDefaultModel = req.updateDefaultModel,
                byIdOnly = true,
            )
            if (updated == null) {
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $id"))
                return@patch
            }
            if (controller.isRelayLiveInitialized) controller.relayLive.refreshBots()
            runCatching { controller.superOrchestrator.refreshBlueprints() }
            call.respond(toLocalBotDto(controller, updated))
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid request"))
        }
    }

    delete("/v1/bots/{id}") {
        val id = call.parameters["id"].orEmpty()
        val remote = controller.findRemoteBotByWorkspaceId(id)
        if (remote != null) {
            when (val res = controller.remoteBotAdminClient.deleteBot(remote.deviceId, remote.botId)) {
                is RemoteAdminResult.Ok -> call.respond(HealthResponse(ok = true))
                is RemoteAdminResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
            }
            return@delete
        }
        try {
            val removed = controller.botRegistry.removeBot(id, byIdOnly = true)
            if (removed == null) {
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $id"))
            } else {
                // Stop active sessions bound to this bot's cwd and disable it in Super PI.
                runCatching {
                    val normRemoved = controller.paths.normalizePath(removed.cwd)
                    controller.runtimeHost.allSessions()
                        .filter { runCatching { controller.paths.normalizePath(it.cwd) }.getOrNull() == normRemoved }
                        .forEach { s ->
                            runCatching { controller.runtimeHost.stop(s.id) }
                            runCatching { controller.runtimeHost.closeSession(s.id) }
                        }
                }
                if (controller.isSuperOrchestratorInitialized) {
                    runCatching {
                        controller.superOrchestrator.setBotEnabledInSuper(
                            SuperRuntime.localBotRef(removed.id),
                            false,
                        )
                    }
                }
                if (controller.isRelayLiveInitialized) controller.relayLive.refreshBots()
                runCatching { controller.superOrchestrator.refreshBlueprints() }
                call.respond(HealthResponse(ok = true))
            }
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Cannot remove bot"))
        }
    }

    get("/v1/bots/{id}/agents-doc") {
        val id = call.parameters["id"].orEmpty()
        val remote = controller.findRemoteBotByWorkspaceId(id)
        if (remote != null) {
            when (val res = controller.remoteBotControlClient.readInstructions(remote.deviceId, remote.botId)) {
                is RemoteControlResult.Ok -> call.respond(
                    AgentDocDto(
                        exists = res.value.content.isNotEmpty(),
                        fileName = "AGENTS.md",
                        content = res.value.content,
                        isOverride = false,
                    ),
                )
                is RemoteControlResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
            }
            return@get
        }
        val bot = controller.botRegistry.findBotById(id)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $id"))
            return@get
        }
        val doc = controller.botRegistry.agentDocStore.read(bot.cwd)
        if (doc == null) {
            call.respond(AgentDocDto(exists = false))
        } else {
            call.respond(
                AgentDocDto(
                    exists = true,
                    fileName = doc.fileName,
                    content = doc.content,
                    isOverride = doc.isOverride,
                ),
            )
        }
    }

    put("/v1/bots/{id}/agents-doc") {
        val id = call.parameters["id"].orEmpty()
        val req = call.receive<SaveAgentDocRequest>()
        if (req.content.toByteArray(Charsets.UTF_8).size > AGENT_DOC_MAX_BYTES) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse("Instructions exceed maximum size of ${AGENT_DOC_MAX_BYTES / 1024} KB."),
            )
            return@put
        }
        val remote = controller.findRemoteBotByWorkspaceId(id)
        if (remote != null) {
            when (val res = controller.remoteBotControlClient.writeInstructions(remote.deviceId, remote.botId, req.content)) {
                is RemoteControlResult.Ok -> call.respond(
                    AgentDocDto(
                        exists = res.value.content.isNotEmpty(),
                        fileName = "AGENTS.md",
                        content = res.value.content,
                        isOverride = false,
                    ),
                )
                is RemoteControlResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
            }
            return@put
        }
        val bot = controller.botRegistry.findBotById(id)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $id"))
            return@put
        }
        val ok = controller.botRegistry.agentDocStore.save(bot.cwd, req.content)
        if (!ok) {
            call.respond(HttpStatusCode.InternalServerError, ApiErrorResponse("Failed to save bot instructions"))
            return@put
        }
        val saved = controller.botRegistry.agentDocStore.read(bot.cwd)
        call.respond(
            if (saved == null) {
                AgentDocDto(exists = false)
            } else {
                AgentDocDto(
                    exists = true,
                    fileName = saved.fileName,
                    content = saved.content,
                    isOverride = saved.isOverride,
                )
            },
        )
    }

    delete("/v1/bots/{id}/agents-doc") {
        val id = call.parameters["id"].orEmpty()
        val remote = controller.findRemoteBotByWorkspaceId(id)
        if (remote != null) {
            when (val res = controller.remoteBotControlClient.writeInstructions(remote.deviceId, remote.botId, "")) {
                is RemoteControlResult.Ok -> call.respond(HealthResponse(ok = true))
                is RemoteControlResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
            }
            return@delete
        }
        val bot = controller.botRegistry.findBotById(id)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $id"))
            return@delete
        }
        controller.botRegistry.agentDocStore.delete(bot.cwd)
        call.respond(HealthResponse(ok = true))
    }

    get("/v1/bots/{id}/policy") {
        val id = call.parameters["id"].orEmpty()
        val remote = controller.findRemoteBotByWorkspaceId(id)
        if (remote != null) {
            when (val res = controller.remoteBotControlClient.getCapabilities(remote.deviceId, remote.botId)) {
                is RemoteControlResult.Ok -> {
                    val snap = res.value
                    val superEnabled = runCatching {
                        controller.superTaskStore.ensure()
                        controller.superTaskStore.isBotEnabled(SuperRuntime.remoteBotRef(remote.deviceId, remote.botId))
                    }.getOrDefault(false)
                    call.respond(
                        BotPolicyDto(
                            allCapabilities = if (snap.isHome) true else snap.policy.allCapabilities,
                            skills = snap.policy.skills.sorted(),
                            mcpServers = snap.policy.mcpServers.sorted(),
                            availableSkills = snap.skills.sorted(),
                            availableMcpServers = snap.mcpServers.sorted(),
                            memoryScope = snap.policy.effectiveMemoryScope().wire.lowercase(),
                            memoryDispatch = snap.policy.effectiveMemoryDispatch(snap.isHome),
                            superEnabled = superEnabled,
                        ),
                    )
                }
                is RemoteControlResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
            }
            return@get
        }
        val bot = controller.botRegistry.findBotById(id)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $id"))
            return@get
        }
        call.respond(buildBotPolicyDto(controller, bot))
    }

    put("/v1/bots/{id}/policy") {
        val id = call.parameters["id"].orEmpty()
        val req = call.receive<UpdateBotPolicyRequest>()
        val remote = controller.findRemoteBotByWorkspaceId(id)
        if (remote != null) {
            val currentRes = controller.remoteBotControlClient.getCapabilities(remote.deviceId, remote.botId)
            val snap = when (currentRes) {
                is RemoteControlResult.Ok -> currentRes.value
                is RemoteControlResult.Error -> {
                    call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(currentRes.message))
                    return@put
                }
            }
            val nextScope = req.memoryScope?.trim()?.takeIf { it.isNotEmpty() }?.let { MemoryAccessScope.fromWire(it) }
                ?: snap.policy.memoryScope
            val nextPolicy = snap.policy.copy(
                allCapabilities = if (snap.isHome) true else (req.allCapabilities ?: snap.policy.allCapabilities),
                skills = req.skills?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: snap.policy.skills,
                mcpServers = req.mcpServers?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: snap.policy.mcpServers,
                memoryScope = nextScope,
                memoryDispatch = when {
                    nextScope == MemoryAccessScope.OFF -> false
                    req.memoryDispatch != null -> req.memoryDispatch
                    else -> snap.policy.memoryDispatch
                },
            )
            when (val setRes = controller.remoteBotControlClient.setCapabilities(remote.deviceId, remote.botId, nextPolicy)) {
                is RemoteControlResult.Ok -> {
                    if (req.superEnabled != null) {
                        runCatching {
                            controller.superTaskStore.ensure()
                            controller.superOrchestrator.setBotEnabledInSuper(
                                SuperRuntime.remoteBotRef(remote.deviceId, remote.botId),
                                req.superEnabled,
                            )
                        }
                    }
                    val updated = setRes.value
                    val superEnabled = runCatching {
                        controller.superTaskStore.ensure()
                        controller.superTaskStore.isBotEnabled(SuperRuntime.remoteBotRef(remote.deviceId, remote.botId))
                    }.getOrDefault(false)
                    call.respond(
                        BotPolicyDto(
                            allCapabilities = if (updated.isHome) true else updated.policy.allCapabilities,
                            skills = updated.policy.skills.sorted(),
                            mcpServers = updated.policy.mcpServers.sorted(),
                            availableSkills = updated.skills.sorted(),
                            availableMcpServers = updated.mcpServers.sorted(),
                            memoryScope = updated.policy.effectiveMemoryScope().wire.lowercase(),
                            memoryDispatch = updated.policy.effectiveMemoryDispatch(updated.isHome),
                            superEnabled = superEnabled,
                        ),
                    )
                }
                is RemoteControlResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(setRes.message))
            }
            return@put
        }

        val bot = controller.botRegistry.findBotById(id)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $id"))
            return@put
        }
        val existing = controller.botRegistry.policyStore.policyForCwd(bot.cwd)
        val nextScope = req.memoryScope?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            MemoryAccessScope.fromWire(raw)
        } ?: existing.memoryScope
        val nextAllCapabilities = if (bot.isHome) {
            true
        } else {
            req.allCapabilities ?: existing.allCapabilities
        }
        val nextDispatch = when {
            nextScope == MemoryAccessScope.OFF -> false
            req.memoryDispatch != null -> req.memoryDispatch
            else -> existing.memoryDispatch
        }
        val updatedPolicy = existing.copy(
            allCapabilities = nextAllCapabilities,
            skills = req.skills?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: existing.skills,
            mcpServers = req.mcpServers?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: existing.mcpServers,
            memoryScope = nextScope,
            memoryDispatch = nextDispatch,
        )
        controller.botRegistry.policyStore.update(bot.cwd, updatedPolicy)
        if (req.superEnabled != null) {
            runCatching {
                controller.superTaskStore.ensure()
                controller.superOrchestrator.setBotEnabledInSuper(
                    SuperRuntime.localBotRef(bot.id),
                    req.superEnabled,
                )
            }
        }
        runCatching { controller.runtimeHost.reconfigure() }
        if (controller.isRelayLiveInitialized) controller.relayLive.refreshBots()
        runCatching { controller.superOrchestrator.refreshBlueprints() }
        call.respond(buildBotPolicyDto(controller, bot))
    }

    get("/v1/bots/{id}/models") {
        val id = call.parameters["id"].orEmpty()
        val sessionId = call.request.queryParameters["sessionId"]?.trim()?.takeIf { it.isNotEmpty() }
        val remote = controller.findRemoteBotByWorkspaceId(id)
        if (remote != null) {
            val peerSessionId = sessionId?.let { RemoteSessionRegistry.peerSessionId(it) }
            when (val res = controller.remoteBotControlClient.fetchModels(remote.deviceId, remote.botId, peerSessionId)) {
                is RemoteControlResult.Ok -> {
                    val snap = res.value
                    val modelsByProvider = snap.models.groupBy { it.providerId }
                    val providers = snap.providers.map { summary ->
                        ProviderDto(
                            id = summary.id,
                            name = summary.name,
                            baseUrl = summary.baseUrl,
                            supportsApiKey = summary.supportsApiKey,
                            hasOAuth = summary.hasOAuth,
                            configured = true,
                            models = modelsByProvider[summary.id].orEmpty().map { m ->
                                ModelOptionDto(
                                    id = m.id,
                                    displayName = m.displayName,
                                    thinkingLevels = m.thinkingLevels.map { it.name },
                                )
                            },
                        )
                    }
                    call.respond(
                        RemoteBotModelsResponse(
                            providers = providers,
                            currentProviderId = snap.currentModel?.providerId,
                            currentModelId = snap.currentModel?.modelId,
                            currentThinkingLevel = snap.currentThinkingLevel?.name,
                        ),
                    )
                }
                is RemoteControlResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
            }
            return@get
        }
        val bot = controller.botRegistry.findBotById(id)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $id"))
            return@get
        }
        val configuredIds = controller.configuredProviderIds()
        val customIds = controller.customProviderStore.entries.value.map { it.id }.toSet()
        val storedKeys = runCatching { controller.apiKeyStore.readAll() }.getOrDefault(emptyMap())
        val providers = controller.providerCatalog.summaries.filter { it.id in configuredIds }.map { summary ->
            val resolvedKey = storedKeys[summary.id]
            val envVal = HostSecretEnv.envSecretFallback("api_key_", summary.id)
            ProviderDto(
                id = summary.id,
                name = summary.name,
                baseUrl = summary.baseUrl,
                supportsApiKey = summary.supportsApiKey,
                hasOAuth = summary.hasOAuth,
                isCustom = summary.id in customIds,
                configured = true,
                keyHint = maskApiKey(resolvedKey ?: envVal),
                hasEnvKey = !envVal.isNullOrBlank() && (resolvedKey.isNullOrBlank() || resolvedKey == envVal),
                models = controller.providerCatalog.models(summary.id).map { m ->
                    ModelOptionDto(
                        id = m.id,
                        displayName = m.displayName,
                        thinkingLevels = m.thinkingLevels.map { it.name },
                    )
                },
            )
        }
        val currentModel = sessionId?.let { runCatching { controller.runtimeHost.sessionModel(it) }.getOrNull() }
        call.respond(
            RemoteBotModelsResponse(
                providers = providers,
                currentProviderId = currentModel?.providerId ?: bot.defaultModel?.providerId,
                currentModelId = currentModel?.modelId ?: bot.defaultModel?.modelId,
                currentThinkingLevel = currentModel?.thinkingLevel?.name ?: bot.defaultModel?.thinkingLevel?.name,
            ),
        )
    }

    get("/v1/bots/{id}/commands") {
        val id = call.parameters["id"].orEmpty()
        val remote = controller.findRemoteBotByWorkspaceId(id)
        if (remote != null) {
            when (val res = controller.remoteBotControlClient.fetchCommands(remote.deviceId, remote.botId)) {
                is RemoteControlResult.Ok -> call.respond(
                    res.value.map { c ->
                        SlashCommandDto(name = c.name, description = c.description, source = c.source)
                    },
                )
                is RemoteControlResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
            }
            return@get
        }
        val bot = controller.botRegistry.findBotById(id)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $id"))
            return@get
        }
        val disabled = controller.settingsStore.current().disabledExtensions.toSet()
        val policy = controller.botRegistry.policyForCwd(bot.cwd)
        val ext = controller.extensionRegistry.snapshot(bot.cwd)
        val enabledSkills = policy.enabledSkills(ext.skills.map { it.name }.toSet())
        val commands = buildList {
            add(SlashCommandDto(name = "compact", description = "Compact conversation context", source = "builtin"))
            if ("builtin:planning" !in disabled) {
                add(SlashCommandDto(name = "goal", description = "Start a multi-step autonomous goal", source = "builtin"))
            }
            ext.skills
                .filter { it.name in enabledSkills && "skill:${it.name}" !in disabled }
                .sortedBy { it.name.lowercase() }
                .forEach { skill ->
                    add(SlashCommandDto(name = "skill:${skill.name}", description = skill.description, source = "skill"))
                }
            ext.prompts
                .filter { "prompt:${it.name}" !in disabled }
                .sortedBy { it.name.lowercase() }
                .forEach { prompt ->
                    add(SlashCommandDto(name = prompt.name, description = prompt.description, source = "prompt"))
                }
        }
        call.respond(commands)
    }
}

private suspend fun toLocalBotDto(controller: SuperPiController, ws: RuntimeWorkspace): BotDto {
    val policy = controller.botRegistry.policyForCwd(ws.cwd)
    return BotDto(
        id = ws.id,
        name = ws.name,
        cwd = ws.cwd,
        isHome = ws.isHome,
        avatar = ws.avatar,
        intro = ws.intro,
        defaultModel = ws.defaultModel?.let { "${it.providerId}/${it.modelId}" },
        defaultThinkingLevel = ws.defaultModel?.thinkingLevel?.name,
        hasAgentsDoc = controller.botRegistry.agentDocStore.read(ws.cwd) != null,
        memoryScope = policy.effectiveMemoryScope().wire.lowercase(),
        memoryDispatch = policy.effectiveMemoryDispatch(ws.isHome),
        isRemote = false,
        deviceName = controller.settingsStore.current().deviceName,
        online = true,
        permissionMode = controller.permissionModeForBot(ws.id).wire,
    )
}

private fun toRemoteBotDto(controller: SuperPiController, rb: RemoteBot): BotDto {
    val dir = RemoteWorkspaces.directory(controller.remoteWorkspaceRoot, rb)
    val caps = rb.botCapabilities
    return BotDto(
        id = RemoteWorkspaces.workspaceId(rb),
        name = rb.botName,
        cwd = dir.absolutePath,
        isHome = rb.botId == "home",
        avatar = rb.avatar,
        intro = rb.intro.takeIf { it.isNotBlank() },
        hasAgentsDoc = false,
        memoryScope = caps?.memoryScope?.lowercase() ?: "personal",
        memoryDispatch = caps?.memoryDispatch ?: false,
        isRemote = true,
        deviceId = rb.deviceId,
        deviceName = rb.deviceName,
        remoteBotId = rb.botId,
        online = rb.online,
        capabilities = rb.capabilities,
    )
}

private suspend fun buildBotPolicyDto(controller: SuperPiController, bot: RuntimeWorkspace): BotPolicyDto {
    val stored = controller.botRegistry.policyStore.policyForCwd(bot.cwd)
    val effectiveAll = if (bot.isHome) true else stored.allCapabilities
    val ext = controller.extensionRegistry.snapshot(bot.cwd)
    val availableSkills = ext.skills.map { it.name }.sorted()
    val availableMcp = ext.mcp.servers.keys.sorted()
    val superEnabled = runCatching {
        controller.superTaskStore.ensure()
        controller.superTaskStore.isBotEnabled(SuperRuntime.localBotRef(bot.id))
    }.getOrDefault(bot.isHome)
    return BotPolicyDto(
        allCapabilities = effectiveAll,
        skills = stored.skills.sorted(),
        mcpServers = stored.mcpServers.sorted(),
        availableSkills = availableSkills,
        availableMcpServers = availableMcp,
        memoryScope = stored.effectiveMemoryScope().wire.lowercase(),
        memoryDispatch = stored.effectiveMemoryDispatch(bot.isHome),
        superEnabled = superEnabled,
    )
}

private fun parseOptionalThinking(raw: String?): ThinkingLevel? {
    val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return ThinkingLevel.entries.firstOrNull { it.name.equals(clean, ignoreCase = true) }
}
