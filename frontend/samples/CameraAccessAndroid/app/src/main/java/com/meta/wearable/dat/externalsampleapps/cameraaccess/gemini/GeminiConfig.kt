package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

object GeminiConfig {
    // Gemini Live through the Mindlogic API gateway. Same protocol as Google's socket; the gateway key goes in
    // the x-goog-api-key header (never in the address, so it stays out of logs).
    const val WEBSOCKET_BASE_URL =
        "wss://factchat-cloud.mindlogic.ai/v1/gateway/gemini/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val MODEL = "models/gemini-3.8-live"
    const val API_KEY_HEADER = "x-goog-api-key"

    const val INPUT_AUDIO_SAMPLE_RATE = 16000
    const val OUTPUT_AUDIO_SAMPLE_RATE = 24000
    const val AUDIO_CHANNELS = 1
    const val AUDIO_BITS_PER_SAMPLE = 16

    const val VIDEO_FRAME_INTERVAL_MS = 1000L
    const val VIDEO_JPEG_QUALITY = 50

    fun systemInstruction(requireWakeWord: Boolean): String {
        val base = SettingsManager.geminiSystemPrompt
        if (requireWakeWord) return base
        return buildString {
            append(base)
            append("\n\n[현재 세션 규칙]\n")
            append("- 이 세션은 앱에서 이미 시작한 활성 대화입니다.\n")
            append("- 이 세션에서는 사용자가 '자비스'를 말하지 않아도 바로 대답하고 도구를 호출하세요.\n")
            append("- 이 세션에서는 웨이크워드 요구 규칙을 적용하지 마세요.\n")
        }
    }

    val apiKey: String
        get() = SettingsManager.geminiAPIKey

    val openClawHost: String
        get() = SettingsManager.openClawHost

    val openClawPort: Int
        get() = SettingsManager.openClawPort

    val openClawHookToken: String
        get() = SettingsManager.openClawHookToken

    val openClawGatewayToken: String
        get() = SettingsManager.openClawGatewayToken

    // Jarvis Memory API (replaces OpenClaw in this fork)
    val jarvisApiBase: String
        get() = SettingsManager.jarvisApiBase

    val jarvisApiKey: String
        get() = SettingsManager.jarvisApiKey

    val isJarvisConfigured: Boolean
        get() = jarvisApiBase != "http://YOUR_HOST:8000"
                && jarvisApiBase.isNotEmpty()
                && jarvisApiKey != "YOUR_JARVIS_API_KEY"
                && jarvisApiKey.isNotEmpty()

    fun websocketURL(): String? {
        if (apiKey == "YOUR_GEMINI_API_KEY" || apiKey.isEmpty()) return null
        return WEBSOCKET_BASE_URL
    }

    val isConfigured: Boolean
        get() = apiKey != "YOUR_GEMINI_API_KEY" && apiKey.isNotEmpty()

    val isOpenClawConfigured: Boolean
        get() = openClawGatewayToken != "YOUR_OPENCLAW_GATEWAY_TOKEN"
                && openClawGatewayToken.isNotEmpty()
                && openClawHost != "http://YOUR_MAC_HOSTNAME.local"
}
