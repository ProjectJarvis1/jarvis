package com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting

data class MeetingHistoryItem(
    val id: String,
    val title: String,
    val startedAt: String,
    val timeDisplay: String,
    val durationDisplay: String?,
    val summary: String?,
    val markdownSummary: String?,
    val transcript: String?,
    val decisions: List<String>,
    val actionItems: List<String>,
    val keywords: List<String>,
    val followups: List<MeetingFollowup> = actionItems.map { MeetingFollowup(it) },
    /** The transcript was edited after the follow-ups were drawn, and they could not be re-checked against it. */
    val followupsNeedReview: Boolean = false,
)

data class MeetingHistoryUiState(
    val isLoading: Boolean = false,
    val meetings: List<MeetingHistoryItem> = emptyList(),
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
)
