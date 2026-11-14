package com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting

import android.app.Application
import android.media.MediaRecorder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MeetingRecordingUiState(
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    val recordingStartedAtMs: Long? = null,
    val errorMessage: String? = null,
    val result: MeetingRecordingResult? = null,
)

class MeetingRecordingViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MeetingRecordingVM"
    }

    private val _uiState = MutableStateFlow(MeetingRecordingUiState())
    val uiState: StateFlow<MeetingRecordingUiState> = _uiState.asStateFlow()

    private val repository = MeetingRecordingRepository()

    init {
        // A recording kept because the day's limit was used up goes up again the next time the app opens.
        viewModelScope.launch { uploadPending() }
    }

    /** Recordings refused by the daily limit wait here, in the account's own folder (account deletion erases it). */
    private fun pendingDir(): File = File(com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount
        .dataDir(getApplication()), "pending-recordings")

    private fun keepForLater(file: File, title: String, startedAt: String, endedAt: String): Boolean = runCatching {
        val dir = pendingDir().apply { mkdirs() }
        val kept = File(dir, file.name)
        if (!file.renameTo(kept)) { file.copyTo(kept, overwrite = true); file.delete() }
        File(dir, "${file.name}.json").writeText(org.json.JSONObject().put("title", title).put("started_at", startedAt)
            .put("ended_at", endedAt).toString())
        true
    }.getOrDefault(false)

    /** Sends kept recordings, oldest first; stops at the first that is still refused or cannot be sent. */
    private suspend fun uploadPending() {
        val files = withContext(Dispatchers.IO) {
            pendingDir().listFiles { f -> f.name.endsWith(".m4a") }?.sortedBy { it.name }.orEmpty()
        }
        for (file in files) {
            if (_uiState.value.isRecording || _uiState.value.isProcessing) return
            val meta = File(file.parentFile, "${file.name}.json")
            // Kept a week at most: an old recording that never went up is dropped with its details.
            if (System.currentTimeMillis() - file.lastModified() > 7 * 24 * 3_600_000L) { file.delete(); meta.delete(); continue }
            val info = runCatching { org.json.JSONObject(meta.readText()) }.getOrNull() ?: run { file.delete(); meta.delete(); null } ?: continue
            _uiState.value = MeetingRecordingUiState(isProcessing = true)
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.upload(file, info.optString("title"), info.optString("started_at"), info.optString("ended_at"))
                }
                file.delete(); meta.delete()
                _uiState.value = MeetingRecordingUiState(result = result)
            } catch (e: Exception) {
                _uiState.value = MeetingRecordingUiState()
                return
            }
        }
    }
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var startedAt: ZonedDateTime? = null

    fun startRecording() {
        if (_uiState.value.isRecording || _uiState.value.isProcessing) return
        val context = getApplication<Application>()
        val file = File(context.cacheDir, "meeting-${System.currentTimeMillis()}.m4a")
        try {
            val mediaRecorder = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(32_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            recordingFile = file
            startedAt = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
            _uiState.value = MeetingRecordingUiState(
                isRecording = true,
                recordingStartedAtMs = System.currentTimeMillis(),
            )
            Log.d(TAG, "Meeting recording started: ${file.absolutePath}")
        } catch (e: Exception) {
            file.delete()
            recorder?.release()
            recorder = null
            _uiState.value = MeetingRecordingUiState(
                errorMessage = "회의 녹음을 시작하지 못했습니다: ${e.message}"
            )
        }
    }

    fun stopAndProcess() {
        if (!_uiState.value.isRecording) return
        val file = recordingFile ?: return
        val start = startedAt ?: ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
        try {
            recorder?.stop()
        } catch (e: Exception) {
            file.delete()
            _uiState.value = MeetingRecordingUiState(
                errorMessage = "녹음 파일을 만들지 못했습니다: ${e.message}"
            )
            releaseRecorder()
            return
        }
        releaseRecorder()
        val end = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
        _uiState.value = MeetingRecordingUiState(isProcessing = true)

        viewModelScope.launch {
            val title = "회의 ${start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}"
            val startedText = start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val endedText = end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.upload(file = file, title = title, startedAt = startedText, endedAt = endedText)
                }
                _uiState.value = MeetingRecordingUiState(result = result)
            } catch (e: QuotaExceeded) {
                // The day's limit, not a broken recording: keep it and send it again the next time the app opens.
                val kept = withContext(Dispatchers.IO) { keepForLater(file, title, startedText, endedText) }
                _uiState.value = MeetingRecordingUiState(errorMessage = if (kept)
                    "${e.message} 녹음은 휴대폰에 남겨 뒀다가 내일 앱을 열면 다시 올려요." else "회의 처리 실패: ${e.message}")
            } catch (e: Exception) {
                _uiState.value = MeetingRecordingUiState(
                    errorMessage = "회의 처리 실패: ${e.message}"
                )
            } finally {
                file.delete()
                recordingFile = null
                startedAt = null
            }
        }
    }

    fun cancelRecording() {
        if (_uiState.value.isRecording) {
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
        }
        releaseRecorder()
        recordingFile?.delete()
        recordingFile = null
        startedAt = null
        _uiState.value = MeetingRecordingUiState()
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null, result = null)
    }

    private fun releaseRecorder() {
        recorder?.release()
        recorder = null
    }

    override fun onCleared() {
        cancelRecording()
        super.onCleared()
    }
}
