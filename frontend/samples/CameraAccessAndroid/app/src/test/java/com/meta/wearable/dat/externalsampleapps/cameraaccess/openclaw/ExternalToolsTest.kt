package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskPolicies
import com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.TaskPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ExternalToolsTest {
    private val body = """
        {"tools": [
          {"name": "notion_search", "title": "노션 검색", "annotations": {"requires_confirmation": false, "source": "notion"},
           "gemini": {"name": "notion_search", "description": "노션 검색",
                      "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}},
          {"name": "slack_send_message", "title": "슬랙 메시지 보내기", "annotations": {"requires_confirmation": true},
           "gemini": {"name": "slack_send_message", "description": "보내기", "parameters": {"type": "object", "properties": {}}}},
          {"name": "universal_search", "title": "겹치는 이름", "gemini": {"name": "universal_search", "description": "x"}},
          {"name": "bad-name!", "title": "잘못된 이름", "gemini": {"name": "bad-name!", "description": "x"}},
          {"name": "no_gemini", "title": "선언 없음"}
        ]}
    """.trimIndent()

    @After fun reset() { ExternalTools.clear(); ExternalTools.reservedNames = emptySet(); TaskPolicies.external = { null } }

    @Test fun parsesOnlySafeNonConflictingTools() {
        val tools = ExternalTools.parse(body, setOf("universal_search"))
        assertEquals(listOf("notion_search", "slack_send_message"), tools.keys.toList())
        assertTrue(tools.getValue("slack_send_message").requiresConfirmation)
        assertFalse(tools.getValue("notion_search").requiresConfirmation)
        val decl = tools.getValue("notion_search").declaration
        assertEquals("BLOCKING", decl.getString("behavior"))
        assertEquals("query", decl.getJSONObject("parameters").getJSONArray("required").getString(0))
    }

    @Test fun previewShowsWhatWillBeSent() {
        ExternalTools.load(body)
        assertEquals("슬랙에 이 메시지를 보낼까요?\n내일 회의 10시로 변경", ExternalTools.preview("slack_send_message", """{"text":"내일 회의 10시로 변경","channel_id":"C1"}"""))
        assertEquals("슬랙에 이 메시지를 보낼까요?", ExternalTools.preview("slack_send_message", "{}"))
    }

    @Test fun runtimePolicyCoversExternalToolsOnly() {
        ExternalTools.load(body)
        TaskPolicies.external = { name -> ExternalTools.get(name)?.let { if (it.requiresConfirmation) TaskPolicy(false, "external-write", true) else TaskPolicy(true) } }
        assertTrue(TaskPolicies.forTool("notion_search").background)
        assertTrue(TaskPolicies.forTool("slack_send_message").write)
        assertTrue(TaskPolicies.forTool("save_person").write) // built-in policies unchanged
        assertThrows(IllegalStateException::class.java) { TaskPolicies.forTool("unknown_tool") }
    }

    @Test fun disconnectedToolKeepsPolicyForStoredTasks() {
        ExternalTools.load(body)
        val seen = ExternalTools.seenJson()
        ExternalTools.clear()
        ExternalTools.restoreSeen(seen)          // app restart
        ExternalTools.load("""{"tools": []}""")  // Notion and Slack disconnected
        TaskPolicies.external = { name -> ExternalTools.everRequiredConfirmation(name)?.let { if (it) TaskPolicy(false, "external-write", true) else TaskPolicy(true) } }
        assertFalse(ExternalTools.isExternal("notion_search"))   // not offered to Gemini any more
        assertTrue(TaskPolicies.forTool("notion_search").background)
        assertTrue(TaskPolicies.forTool("slack_send_message").write)
    }

    @Test fun brokenBodyKeepsPreviousList() {
        ExternalTools.load(body)
        runCatching { ExternalTools.load("not json") }
        assertEquals(2, ExternalTools.all().size)
    }
}
