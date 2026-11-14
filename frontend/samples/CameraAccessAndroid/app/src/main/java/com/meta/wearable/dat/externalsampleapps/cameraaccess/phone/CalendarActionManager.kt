package com.meta.wearable.dat.externalsampleapps.cameraaccess.phone

import android.content.Intent
import android.provider.CalendarContract
import com.meta.wearable.dat.externalsampleapps.cameraaccess.AppContextProvider
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import java.time.Duration

class CalendarActionManager {
    private val context
        get() = AppContextProvider.require()

    fun createEvent(
        title: String,
        startAt: String,
        endAt: String?,
        location: String?,
        description: String?,
    ): ToolResult {
        if (title.isBlank()) {
            return ToolResult.Failure("일정 제목이 필요합니다.")
        }
        val startMillis = parseMillis(startAt)
            ?: return ToolResult.Failure("일정 시작 시간을 해석하지 못했습니다.")
        val endMillis = endAt?.let(::parseMillis) ?: (startMillis + Duration.ofHours(1).toMillis())
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            location?.takeIf { it.isNotBlank() }?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            description?.takeIf { it.isNotBlank() }?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult.Success("$title 일정 등록 화면을 열었습니다. 내용을 확인한 뒤 저장해주세요.")
    }

    // Same reading as the approval card and the server's Google Calendar write: the clock time as said, in
    // Korea time, whatever offset the model attached.
    private fun parseMillis(value: String): Long? =
        com.meta.wearable.dat.externalsampleapps.cameraaccess.tasks.CalendarEventPreview.koreaWallTime(value)
            ?.toInstant()?.toEpochMilli()
}
