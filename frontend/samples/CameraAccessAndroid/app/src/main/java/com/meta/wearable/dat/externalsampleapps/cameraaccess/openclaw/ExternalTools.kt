package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tools the Jarvis server exposes from outside services (Notion, Slack, Google Calendar).
 *
 * The list is fetched from `/mcp/tools` before each Live session and declared to Gemini next to the app's
 * own tools. Calls go back to the server's `/mcp` endpoint. A tool marked `requires_confirmation` waits for
 * the on-screen card or a spoken "확인" before it runs. When the server is unreachable the last list that
 * worked is used, so starting a conversation never waits on the server.
 */
object ExternalTools {
    data class Tool(
        val name: String,
        val title: String,
        val requiresConfirmation: Boolean,
        val source: String,
        val declaration: JSONObject,
    )

    @Volatile private var tools: Map<String, Tool> = emptyMap()
    // Every external tool ever listed, with whether it needed approval. Kept after a service disconnects so
    // stored tasks that used it still resolve a policy (TaskManager looks up every stored record).
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Names the app already declares. A server tool with the same name is ignored. */
    var reservedNames: Set<String> = emptySet()

    fun all(): List<Tool> = tools.values.toList()
    fun get(name: String): Tool? = tools[name]
    fun isExternal(name: String): Boolean = name in tools
    fun requiresConfirmation(name: String): Boolean = tools[name]?.requiresConfirmation == true
    fun title(name: String): String? = tools[name]?.title

    /** Parses a `/mcp/tools` body and replaces the current list. Returns how many tools were accepted. */
    fun load(body: String): Int {
        tools = parse(body, reservedNames + appToolNames)
        tools.values.forEach { seen[it.name] = it.requiresConfirmation }
        return tools.size
    }

    fun clear() { tools = emptyMap(); seen.clear() }

    /** Approval flag of a tool listed now or earlier; null if this name was never an external tool. */
    fun everRequiredConfirmation(name: String): Boolean? = tools[name]?.requiresConfirmation ?: seen[name]

    fun seenJson(): String = JSONObject(seen.toMap()).toString()

    fun restoreSeen(json: String) {
        val obj = JSONObject(json)
        obj.keys().forEach { name -> if (NAME.matches(name) && name !in appToolNames) seen[name] = obj.optBoolean(name, true) }
    }

    fun declarations(): List<JSONObject> = tools.values.map { JSONObject(it.declaration.toString()) }

    fun parse(body: String, reserved: Set<String>): Map<String, Tool> {
        val list = JSONObject(body).optJSONArray("tools") ?: JSONArray()
        val out = linkedMapOf<String, Tool>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (!NAME.matches(name) || name in reserved || name in out) continue
            val gemini = item.optJSONObject("gemini") ?: continue
            val params = gemini.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject())
            val annotations = item.optJSONObject("annotations") ?: JSONObject()
            val declaration = JSONObject()
                .put("name", name)
                .put("description", gemini.optString("description").take(1000))
                .put("parameters", params)
                .put("behavior", "BLOCKING")
            out[name] = Tool(
                name = name,
                title = item.optString("title").ifBlank { name },
                requiresConfirmation = annotations.optBoolean("requires_confirmation", false),
                source = annotations.optString("source"),
                declaration = declaration,
            )
        }
        return out
    }

    /** What the confirm card shows for an external write, from the tool's arguments. */
    fun preview(name: String, argsJson: String): String {
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
        if (name == "gcal_create_event" || name == "calendar_create_event") {
            // The time is what the user must catch before approving, so it leads the second line.
            val start = args.optString("start").replace('T', ' ').take(16)
            val end = args.optString("end").replace('T', ' ').take(16).let { if (it.take(10) == start.take(10)) it.drop(11) else it }
            val time = if (end.isBlank()) start else "$start ~ $end"
            val place = args.optString("location").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            return "구글 캘린더에 '${args.optString("title")}' 일정을 넣을까요?\n$time$place"
        }
        val body = listOf("text", "message", "content", "summary", "query", "title").firstNotNullOfOrNull { key ->
            args.optString(key).takeIf { it.isNotBlank() }
        }
        // Name the destination so the user can catch a wrong channel or page before approving.
        val channel = args.keys().asSequence().firstOrNull { it.contains("channel") }?.let { args.optString(it) }?.takeIf { it.isNotBlank() }
        val page = args.optString("page_title").takeIf { it.isNotBlank() }
        val newPage = args.optString("title").takeIf { it.isNotBlank() && args.has("content") }
        val headline = when {
            name.startsWith("slack_") -> if (channel != null) "슬랙 $channel 에 이 메시지를 보낼까요?" else "슬랙에 이 메시지를 보낼까요?"
            name.startsWith("notion_") && newPage != null -> "노션에 '$newPage' 페이지를 만들까요?"
            name.startsWith("notion_") -> if (page != null) "노션 '$page' 페이지에 이 내용을 추가할까요?" else "노션에 이 내용을 추가할까요?"
            name.startsWith("gcal_") -> "구글 캘린더에 반영할까요?"
            else -> "${title(name) ?: name}, 실행할까요?"
        }
        return if (body != null) "$headline\n$body" else headline
    }

    /** Error text of a JSON-RPC `/mcp` response that failed (error object or result.isError); null otherwise. */
    fun rpcFailure(body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optString("jsonrpc") != "2.0") return null
        json.optJSONObject("error")?.let { return it.optString("message", "도구 실행 실패") }
        val result = json.optJSONObject("result") ?: return null
        if (!result.optBoolean("isError")) return null
        val content = result.optJSONArray("content")
        return (0 until (content?.length() ?: 0)).joinToString("") { content!!.optJSONObject(it)?.optString("text").orEmpty() }
            .ifBlank { "도구 실행 실패" }
    }

    private val appToolNames: Set<String> by lazy {
        val declarations = ToolDeclarations.allDeclarationsJSON(liveConversation = true)
        (0 until declarations.length()).map { declarations.getJSONObject(it).getString("name") }.toSet()
    }

    private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
}
