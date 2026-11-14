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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
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
)

class MeetingRecordingViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MeetingRecordingVM"
    }

    private val _uiState = MutableStateFlow(MeetingRecordingUiState())
    val uiState: StateFlow<MeetingRecordingUiState> = _uiState.asStateFlow()

    private val repository = MeetingRecordingRepository()

    // Each finished upload is its own event: several kept recordings sent in a row must each reach the screen,
    // which a single state field would overwrite before it is read.
    private val _results = Channel<MeetingRecordingResult>(Channel.UNLIMITED)
    val results: Flow<MeetingRecordingResult> = _results.receiveAsFlow()

    private val drainLock = Mutex()

    // Kept recordings go up again whenever the app comes to the front, not only on a fresh start.
    private val foregroundObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) viewModelScope.launch { uploadPending() }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        viewModelScope.launch { uploadPending() }
    }

    /** Recordings refused by the daily limit wait here, in the account's own folder (account deletion erases it). */
    private fun queue(): PendingRecordingQueue {
        val dir = com.meta.wearable.dat.externalsampleapps.cameraaccess.account.JarvisAccount.dataDir(getApplication())
        return PendingRecordingQueue(File(dir, "pending-recordings"), File(dir, "failed-recordings"))
    }

    private fun keepForLater(file: File, title: String, startedAt: String, endedAt: String): Boolean =
        queue().keep(file, title, startedAt, endedAt)

    /** Sends kept recordings, oldest first. One run at a time; a new recording pauses it. */
    private suspend fun uploadPending() {
        if (!drainLock.tryLock()) return
        try {
            if (_uiState.value.isRecording || _uiState.value.isProcessing) return
            val q = withContext(Dispatchers.IO) { queue() }
            var showing = false
            q.drain(
                upload = { item ->
                    if (!showing) { _uiState.value = _uiState.value.copy(isProcessing = true); showing = true }
                    withContext(Dispatchers.IO) { repository.upload(item.file, item.title, item.startedAt, item.endedAt) }
                },
                onResult = { _results.send(it) },
                onPermanentFailure = { item, e ->
                    _uiState.value = _uiState.value.copy(errorMessage =
                        "보관해 둔 ${item.title} 녹음은 정리하지 못했어요. ${e.message.orEmpty()}".trim())
                },
                shouldStop = { _uiState.value.isRecording },
            )
            if (showing && !_uiState.value.isRecording) _uiState.value = _uiState.value.copy(isProcessing = false)
        } finally {
            drainLock.unlock()
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
                _uiState.value = MeetingRecordingUiState()
                _results.send(result)
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
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun releaseRecorder() {
        recorder?.release()
        recorder = null
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
        cancelRecording()
        super.onCleared()
    }
}
