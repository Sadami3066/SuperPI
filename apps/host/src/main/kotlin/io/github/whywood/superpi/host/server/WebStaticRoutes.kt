package io.github.whywood.superpi.host.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File
import java.net.JarURLConnection

internal object WebUiAssets {
    private const val RESOURCE_ROOT = "web-ui"

    fun loadResource(requestPath: String): Pair<ContentType, ByteArray>? {
        val cleaned = requestPath.trim().trimStart('/')
        val rel = if (cleaned.isEmpty()) "index.html" else cleaned
        if (rel.contains("..") || rel.contains('\\') || rel.contains('\u0000')) return null
        val cl = WebUiAssets::class.java.classLoader
        val resourcePath = "$RESOURCE_ROOT/$rel"
        val url = cl.getResource(resourcePath) ?: return null
        if (url.protocol == "file") {
            val file = runCatching { File(url.toURI()) }.getOrNull() ?: return null
            if (!file.isFile) return null
        } else if (url.protocol == "jar") {
            val conn = runCatching { url.openConnection() as? JarURLConnection }.getOrNull()
            if (conn?.jarEntry?.isDirectory == true) return null
        }
        val bytes = cl.getResourceAsStream(resourcePath)?.use { it.readBytes() } ?: return null
        return contentTypeFor(rel) to bytes
    }

    fun loadIndexHtml(): ByteArray? =
        WebUiAssets::class.java.classLoader.getResourceAsStream("$RESOURCE_ROOT/index.html")?.use { it.readBytes() }

    private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> ContentType.Text.Html
        "js", "mjs" -> ContentType.Application.JavaScript
        "css" -> ContentType.Text.CSS
        "json" -> ContentType.Application.Json
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "webp" -> ContentType("image", "webp")
        "ico" -> ContentType("image", "x-icon")
        "woff2" -> ContentType("font", "woff2")
        "woff" -> ContentType("font", "woff")
        "ttf" -> ContentType("font", "ttf")
        "otf" -> ContentType("font", "otf")
        else -> ContentType.Application.OctetStream
    }

    const val FALLBACK_HTML = """<!doctype html>
<html lang="zh-CN">
<head><meta charset="UTF-8"><title>Super PI</title></head>
<body style="font-family:system-ui,sans-serif;padding:48px;color:#111;background:#f7f7f8">
  <h2>Super PI 本地服务已就绪</h2>
  <p>当前构建未内嵌前端资源包。请先执行 <code>pnpm --filter @superpi/web build</code> 后重新打包。</p>
</body>
</html>"""
}

internal fun Route.installWebStaticRoutes() {
    get("/") {
        val index = WebUiAssets.loadIndexHtml()
        if (index != null) {
            call.respondBytes(index, ContentType.Text.Html)
        } else {
            call.respondText(WebUiAssets.FALLBACK_HTML, ContentType.Text.Html)
        }
    }

    get("/{path...}") {
        val rawSegments = call.parameters.getAll("path").orEmpty()
        val joined = rawSegments.joinToString("/")
        if (joined.startsWith("api/") || joined == "api") {
            call.respondText(
                text = """{"error":"Not found"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.NotFound,
            )
            return@get
        }
        val asset = WebUiAssets.loadResource(joined)
        if (asset != null) {
            call.respondBytes(asset.second, asset.first)
            return@get
        }
        val last = rawSegments.lastOrNull().orEmpty()
        if (!last.contains('.')) {
            val index = WebUiAssets.loadIndexHtml()
            if (index != null) {
                call.respondBytes(index, ContentType.Text.Html)
                return@get
            }
            call.respondText(WebUiAssets.FALLBACK_HTML, ContentType.Text.Html)
            return@get
        }
        call.respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
    }
}
