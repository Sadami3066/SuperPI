package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.data.relay.RelayException
import io.github.whywood.superpi.host.core.SuperPiController
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.CancellationException

internal fun Route.installRemoteDeviceRoutes(controller: SuperPiController) {
    get("/v1/relay/devices") {
        val account = controller.relayStore.read()
        val selfDeviceId = account?.deviceId
        val devices = if (controller.isRelayLiveInitialized) {
            controller.relayLive.devices.value
        } else {
            runCatching { controller.relayStore.readDevices() }.getOrDefault(emptyList())
        }
        val list = devices.map { dev ->
            RelayDeviceDto(
                deviceId = dev.deviceId,
                name = dev.name,
                platform = dev.platform,
                online = dev.online,
                lastSeen = dev.lastSeen,
                botCount = dev.bots.size,
                capabilities = dev.capabilities,
                isSelf = dev.deviceId == selfDeviceId,
            )
        }
        call.respond(list)
    }

    patch("/v1/relay/devices/{id}") {
        val deviceId = call.parameters["id"].orEmpty().trim()
        val req = call.receive<RenameDeviceRequest>()
        val cleanName = req.name.trim()
        if (deviceId.isEmpty() || cleanName.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Device ID and name are required"))
            return@patch
        }
        val account = controller.relayStore.read()
        val token = runCatching { controller.relayStore.token() }.getOrNull()
        if (account == null || token.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Not signed in to Relay"))
            return@patch
        }
        try {
            controller.relayClient.renameDevice(account, token, deviceId, cleanName)
            if (controller.isRelayLiveInitialized) {
                controller.relayLive.updateDeviceName(deviceId, cleanName)
            }
            if (deviceId == account.deviceId) {
                controller.settingsStore.update { it.copy(deviceName = cleanName) }
                controller.relayStore.save(account.copy(deviceName = cleanName), token)
            }
            call.respond(HealthResponse(ok = true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: RelayException) {
            call.respond(HttpStatusCode.BadGateway, ApiErrorResponse("Failed to rename device: ${err.failure.name}"))
        } catch (err: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ApiErrorResponse(err.message ?: "Failed to rename device"))
        }
    }

    delete("/v1/relay/devices/{id}") {
        val deviceId = call.parameters["id"].orEmpty().trim()
        if (deviceId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Device ID is required"))
            return@delete
        }
        val account = controller.relayStore.read()
        val token = runCatching { controller.relayStore.token() }.getOrNull()
        if (account == null || token.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Not signed in to Relay"))
            return@delete
        }
        try {
            controller.relayClient.revokeDevice(account, token, deviceId)
            if (deviceId == account.deviceId) {
                controller.relayStore.clear()
                controller.reloadAll()
            } else if (controller.isRelayLiveInitialized) {
                controller.relayLive.removeDevice(deviceId)
            }
            call.respond(HealthResponse(ok = true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: RelayException) {
            call.respond(HttpStatusCode.BadGateway, ApiErrorResponse("Failed to remove device: ${err.failure.name}"))
        } catch (err: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ApiErrorResponse(err.message ?: "Failed to remove device"))
        }
    }

    put("/v1/relay/allow-remote") {
        val req = call.receive<AllowRemoteRequest>()
        controller.relayStore.setAllowRemote(req.allowRemote)
        val account = controller.relayStore.read()
        call.respond(
            RelayStatusDto(
                enrolled = account != null,
                state = if (controller.isRelayLiveInitialized) controller.relayLive.state.value.name else "Disabled",
                origin = account?.origin,
                deviceId = account?.deviceId,
                deviceName = account?.deviceName,
                allowRemote = controller.relayStore.readAllowRemote(),
            ),
        )
    }

    get("/v1/questions") {
        call.respond(controller.questionCenter.pending.value.map { it.toDto() })
    }

    post("/v1/questions/{requestId}/answer") {
        val requestId = call.parameters["requestId"].orEmpty().trim()
        val req = call.receive<AnswerQuestionRequest>()
        if (requestId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Missing requestId"))
            return@post
        }
        val exists = controller.questionCenter.pending.value.any { it.requestId == requestId }
        if (!exists) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Pending question not found: $requestId"))
            return@post
        }
        controller.questionCenter.answer(requestId, req.answers.map { it.toDomain() })
        call.respond(HealthResponse(ok = true))
    }

    post("/v1/questions/{requestId}/dismiss") {
        val requestId = call.parameters["requestId"].orEmpty().trim()
        if (requestId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Missing requestId"))
            return@post
        }
        val exists = controller.questionCenter.pending.value.any { it.requestId == requestId }
        if (!exists) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Pending question not found: $requestId"))
            return@post
        }
        controller.questionCenter.cancel(requestId)
        call.respond(HealthResponse(ok = true))
    }
}
