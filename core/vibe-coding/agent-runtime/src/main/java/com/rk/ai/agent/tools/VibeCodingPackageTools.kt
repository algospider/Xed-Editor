@file:OptIn(ExperimentalUuidApi::class)

package com.rk.ai.agent.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.rk.ai.models.InputSchema
import com.rk.ai.models.Tool
import com.rk.ai.models.UIMessagePart
import com.rk.ai.service.IdeService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class VibeCodingPackageTools(private val ideService: IdeService) {

    private val npmSearch = Tool(
        name = "npmSearch",
        description = "Search the npm registry for JavaScript/TypeScript packages. " +
            "Returns name, version, description, and publisher. " +
            "Use when a project uses npm and you need to find a package. " +
            "Example: {\"query\": \"react\", \"limit\": 5}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Package name or search term") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Maximum results (default: 10, max: 50)") }
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val query = obj["query"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'query'."))
            val limit = (obj["limit"]?.asJsonPrimitive?.asInt ?: 10).coerceIn(1, 50)

            try {
                val url = "https://registry.npmjs.org/-/v1/search?text=${URLEncoder.encode(query, "UTF-8")}&size=$limit"
                val json = httpGet(url)
                val data = JsonParser.parseString(json).asJsonObject
                val objects = data.getAsJsonArray("objects") ?: JsonArray()

                val text = buildString {
                    appendLine("npm search results for: $query")
                    appendLine()
                    if (objects.size() == 0) appendLine("No packages found.")
                    objects.forEach { objEntry ->
                        val pkg = objEntry.asJsonObject?.getAsJsonObject("package") ?: return@forEach
                        val name = pkg.get("name")?.asString ?: "?"
                        val version = pkg.get("version")?.asString ?: "?"
                        val description = pkg.get("description")?.asString ?: ""
                        val publisher = pkg.getAsJsonObject("publisher")?.get("username")?.asString
                            ?: pkg.getAsJsonObject("author")?.get("name")?.asString ?: "?"
                        appendLine("$name@$version")
                        if (description.isNotBlank()) appendLine("  $description")
                        appendLine("  Publisher: $publisher")
                        appendLine()
                    }
                }
                listOf(UIMessagePart.Text(text))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("ERROR: npm search failed: ${e.message ?: "unknown"}. SUGGESTION: Check connectivity or try a broader query."))
            }
        },
    )

    private val pipSearch = Tool(
        name = "pipSearch",
        description = "Search PyPI (Python Package Index) for packages. " +
            "Returns name, version, description. Use when a project uses Python. " +
            "Example: {\"query\": \"flask\", \"limit\": 5}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Package name or search term") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Maximum results (default: 10, max: 30)") }
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val query = obj["query"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'query'."))
            val limit = (obj["limit"]?.asJsonPrimitive?.asInt ?: 10).coerceIn(1, 30)

            try {
                val html = httpGet("https://pypi.org/search/?q=${URLEncoder.encode(query, "UTF-8")}")
                val packages = mutableListOf<String>()
                val snippetRegex = Regex("<a class=\"package-snippet\"[^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL)
                for (match in snippetRegex.findAll(html).take(limit)) {
                    val block = match.value
                    val name = Regex("<span class=\"package-snippet__name\">(.*?)</span>").find(block)?.groupValues?.getOrNull(1)?.trim() ?: "?"
                    val version = Regex("<span class=\"package-snippet__version\">(.*?)</span>").find(block)?.groupValues?.getOrNull(1)?.trim() ?: "?"
                    val desc = Regex("<p class=\"package-snippet__description\">(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.getOrNull(1)?.trim()?.replace(Regex("<[^>]*>"), "") ?: ""
                    packages.add("$name $version${if (desc.isNotEmpty()) " — $desc" else ""}")
                }
                if (packages.isEmpty()) {
                    listOf(UIMessagePart.Text("No packages found for: $query. SUGGESTION: Try a broader search term or check the name."))
                } else {
                    listOf(UIMessagePart.Text("PyPI results for: $query\n${packages.joinToString("\n")}"))
                }
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("ERROR: PyPI search failed: ${e.message ?: "unknown"}. SUGGESTION: Check connectivity."))
            }
        },
    )

    private val mavenSearch = Tool(
        name = "mavenSearch",
        description = "Search Maven Central for Java/Kotlin artifacts. " +
            "Returns groupId:artifactId, latest version, and last updated date. " +
            "Use when a project uses Gradle/Maven and you need to find a library. " +
            "Example: {\"query\": \"com.google.guava:guava\", \"limit\": 5} " +
            "Also works: {\"query\": \"kotlinx coroutines\", \"limit\": 3}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Search term (groupId:artifactId or name)") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Maximum results (default: 10, max: 50)") }
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val query = obj["query"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'query'."))
            val limit = (obj["limit"]?.asJsonPrimitive?.asInt ?: 10).coerceIn(1, 50)

            try {
                val url = "https://search.maven.org/solrsearch/select?q=${URLEncoder.encode(query, "UTF-8")}&rows=$limit&wt=json"
                val json = httpGet(url)
                val data = JsonParser.parseString(json).asJsonObject
                val docs = data.getAsJsonObject("response")?.getAsJsonArray("docs") ?: JsonArray()

                val text = buildString {
                    appendLine("Maven Central results for: $query")
                    appendLine()
                    if (docs.size() == 0) appendLine("No artifacts found.")
                    docs.forEach { doc ->
                        val docObj = doc.asJsonObject
                        val g = docObj.get("g")?.asString ?: "?"
                        val a = docObj.get("a")?.asString ?: "?"
                        val latestVersion = docObj.get("latestVersion")?.asString ?: "?"
                        val timestamp = docObj.get("timestamp")?.asLong ?: 0L
                        appendLine("$g:$a")
                        appendLine("  Latest: $latestVersion")
                        if (timestamp > 0L) {
                            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                                timeZone = java.util.TimeZone.getTimeZone("UTC")
                            }.format(java.util.Date(timestamp))
                            appendLine("  Updated: $date")
                        }
                        appendLine()
                    }
                }
                listOf(UIMessagePart.Text(text))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("ERROR: Maven search failed: ${e.message ?: "unknown"}.\nSUGGESTION: Try groupId:artifactId format (e.g. com.google.guava:guava) or check connectivity."))
            }
        },
    )

    private val goSearch = Tool(
        name = "goSearch",
        description = "Search Go packages from pkg.go.dev. " +
            "Returns package path, synopsis, and import count. " +
            "Use when a project uses Go modules. " +
            "Example: {\"query\": \"gorilla/mux\", \"limit\": 5}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Package name or search term") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Maximum results (default: 10, max: 30)") }
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val query = obj["query"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'query'."))
            val limit = (obj["limit"]?.asJsonPrimitive?.asInt ?: 10).coerceIn(1, 30)

            try {
                val json = httpGet("https://api.godoc.org/search?q=${URLEncoder.encode(query, "UTF-8")}")
                val data = JsonParser.parseString(json).asJsonObject
                val results = data.getAsJsonArray("results") ?: JsonArray()
                if (results.size() == 0) return@Tool listOf(UIMessagePart.Text("No Go packages found for: $query"))
                val text = buildString {
                    appendLine("Go packages for: $query")
                    results.take(limit).forEachIndexed { i, item ->
                        val obj = item.asJsonObject
                        appendLine("${i + 1}. ${obj.get("path")?.asString ?: "?"}")
                        val synopsis = obj.get("synopsis")?.asString ?: ""
                        if (synopsis.isNotBlank()) appendLine("   $synopsis")
                        val imports = obj.get("import_count")?.asInt ?: 0
                        appendLine("   Imports: $imports")
                    }
                }
                listOf(UIMessagePart.Text(text))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("ERROR: Go search failed: ${e.message ?: "unknown"}. SUGGESTION: Try a simpler query or check connectivity."))
            }
        },
    )

    val all: List<Tool> = listOf(npmSearch, pipSearch, mavenSearch, goSearch)

    private fun httpGet(urlStr: String): String {
        val url = URI(urlStr).toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "Xed-Editor/2.0")
        conn.setRequestProperty("Accept", "application/json")

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) throw RuntimeException("HTTP $responseCode")
        return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
    }
}
