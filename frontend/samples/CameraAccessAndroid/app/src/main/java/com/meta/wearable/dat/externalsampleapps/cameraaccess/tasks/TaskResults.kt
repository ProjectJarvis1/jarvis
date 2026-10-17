package com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Keep the history valid and bounded; the full payload is archived separately. */
object TaskResults {
    const val INLINE_LIMIT = 64_000
    fun preview(raw: String?): String? {
        if (raw == null || raw.length <= INLINE_LIMIT) return raw
        fun compact(value: Any?, depth: Int): Any? = when {
            depth > 8 -> "추가 내용은 전체 결과에서 확인하세요."
            value is JSONObject -> JSONObject().apply {
                value.keys().asSequence().take(40).forEach { put(it, compact(value.opt(it), depth + 1)) }
            }
            value is JSONArray -> JSONArray((0 until minOf(value.length(), 8)).map { compact(value.opt(it), depth + 1) })
            value is String -> value.take(512)
            else -> value
        }
        val compact = runCatching { JSONObject.valueToString(compact(JSONTokener(raw).nextValue(), 0)) }.getOrNull()
        return compact?.takeIf { it.length <= INLINE_LIMIT }
            ?: JSONObject().put("summary", raw.take(4_000)).put("additional_content", true).toString()
    }
}
