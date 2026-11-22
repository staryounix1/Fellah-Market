package com.example.core.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class VoiceUiState(
    val isRecording: Boolean = false,
    val recordedSeconds: Int = 0,
    val isPlaying: Boolean = false,
    val playingPath: String? = null,
    val playbackProgressSec: Int = 0,
    val errorMessage: String? = null
)

class VoiceNoteManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var timerJob: Job? = null
    private var currentFilePath: String? = null

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    fun startRecording(onMaxDurationReached: (String, Int) -> Unit) {
        stopPlayback()
        runCatching {
            val dir = File(context.filesDir, "voice_notes").apply { mkdirs() }
            val file = File(dir, "voice_${UUID.randomUUID()}.m4a")
            currentFilePath = file.absolutePath

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(32_000)
                setAudioSamplingRate(16_000)
                setMaxDuration(60_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            _state.value = VoiceUiState(isRecording = true, recordedSeconds = 0)
            timerJob?.cancel()
            timerJob = scope.launch {
                for (sec in 1..60) {
                    delay(1000L)
                    if (!_state.value.isRecording) break
                    _state.value = _state.value.copy(recordedSeconds = sec)
                    if (sec >= 60) {
                        val result = stopRecording()
                        if (result != null) {
                            onMaxDurationReached(result.first, result.second)
                        }
                        break
                    }
                }
            }
        }.onFailure { err ->
            _state.value = _state.value.copy(isRecording = false, errorMessage = err.localizedMessage)
        }
    }

    fun stopRecording(): Pair<String, Int>? {
        timerJob?.cancel()
        val elapsed = _state.value.recordedSeconds.coerceAtLeast(1)
        val path = currentFilePath
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        recorder = null
        _state.value = _state.value.copy(isRecording = false)
        return if (path != null && File(path).exists()) path to elapsed else null
    }

    fun togglePlayback(path: String) {
        if (_state.value.isPlaying && _state.value.playingPath == path) {
            stopPlayback()
            return
        }
        stopPlayback()
        runCatching {
            val mp = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
            }
            player = mp
            _state.value = _state.value.copy(isPlaying = true, playingPath = path, playbackProgressSec = 0)
            mp.setOnCompletionListener {
                stopPlayback()
            }
            timerJob?.cancel()
            timerJob = scope.launch {
                while (_state.value.isPlaying) {
                    delay(500L)
                    val sec = (player?.currentPosition ?: 0) / 1000
                    _state.value = _state.value.copy(playbackProgressSec = sec)
                }
            }
        }.onFailure {
            stopPlayback()
        }
    }

    fun stopPlayback() {
        timerJob?.cancel()
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        _state.value = _state.value.copy(isPlaying = false, playingPath = null, playbackProgressSec = 0)
    }

    fun release() {
        stopRecording()
        stopPlayback()
    }
}
