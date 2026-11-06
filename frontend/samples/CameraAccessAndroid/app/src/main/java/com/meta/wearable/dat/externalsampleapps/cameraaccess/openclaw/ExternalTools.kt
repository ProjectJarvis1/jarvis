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
        return tools.size
    }

    fun clear() { tools = emptyMap() }

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
        val body = listOf("text", "message", "title", "summary", "query").firstNotNullOfOrNull { key ->
            args.optString(key).takeIf { it.isNotBlank() }
        }
        val headline = when {
            name.startsWith("slack_") -> "슬랙에 이 메시지를 보낼까요?"
            name.startsWith("notion_") -> "노션에 이 내용을 추가할까요?"
            name.startsWith("gcal_") -> "구글 캘린더에 반영할까요?"
            else -> "${title(name) ?: name}, 실행할까요?"
        }
        return if (body != null) "$headline\n$body" else headline
    }

    private val appToolNames: Set<String> by lazy {
        val declarations = ToolDeclarations.allDeclarationsJSON(liveConversation = true)
        (0 until declarations.length()).map { declarations.getJSONObject(it).getString("name") }.toSet()
    }

    private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
}
