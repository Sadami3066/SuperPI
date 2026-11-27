package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.host.market.MarketInstallRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException

internal fun Route.installMarketRoutes(controller: SuperPiController) {
    get("/v1/market/entries") {
        val typeFilter = call.request.queryParameters["type"]
        try {
            val catalog = controller.marketService.listEntries(typeFilter = typeFilter, forceRefresh = false)
            call.respond(catalog)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid market request"))
        } catch (err: Exception) {
            call.respond(
                HttpStatusCode.BadGateway,
                ApiErrorResponse(err.message ?: "Unable to load market catalog"),
            )
        }
    }

    post("/v1/market/refresh") {
        val typeFilter = call.request.queryParameters["type"]
        try {
            val catalog = controller.marketService.listEntries(typeFilter = typeFilter, forceRefresh = true)
            call.respond(catalog)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid market request"))
        } catch (err: Exception) {
            call.respond(
                HttpStatusCode.BadGateway,
                ApiErrorResponse(err.message ?: "Unable to refresh market catalog"),
            )
        }
    }

    post("/v1/market/install") {
        val req = call.receive<MarketInstallRequest>()
        try {
            val res = controller.marketService.install(req.entryId, req.apiKey)
            call.respond(res)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: NoSuchElementException) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse(err.message ?: "Market entry not found"))
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid install request"))
        } catch (err: Exception) {
            call.respond(
                HttpStatusCode.BadGateway,
                ApiErrorResponse(err.message ?: "Market install failed"),
            )
        }
    }
}
