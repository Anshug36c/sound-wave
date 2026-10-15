package echo.music.iad1tya.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import echo.music.iad1tya.generate.GenerationState
import echo.music.iad1tya.generate.GenerationStatus
import echo.music.iad1tya.generate.LocalTasteEngine
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the "Create from Taste" quick action.
 *
 * Soundwave ships no cloud AI prompt providers, so this ViewModel exposes only the offline
 * taste path of the upstream generator: recommendations come from [LocalTasteEngine], which
 * scores the locally stored listening history and never performs a prompt/API round trip.
 */
@HiltViewModel
class CreateFromTasteViewModel
@Inject
constructor(
  private val generationStatus: GenerationStatus,
  private val localTasteEngine: LocalTasteEngine,
) : ViewModel() {

  private var isTasteGenerating = false

  private val _isGenerating = MutableStateFlow(false)
  val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

  private val _generationLog = MutableStateFlow(INITIAL_LOG)
  val generationLog: StateFlow<String> = _generationLog.asStateFlow()

  private val _errorLog = MutableStateFlow<String?>(null)
  val errorLog: StateFlow<String?> = _errorLog.asStateFlow()

  private var generateTasteJob: Job? = null

  init {
    viewModelScope.launch {
      generationStatus.state.collect { state ->
        // Only mirror status changes caused by this screen; the Generate screen owns its own.
        if (!isTasteGenerating) return@collect
        when (state) {
          is GenerationState.Running -> {
            _isGenerating.value = true
            _generationLog.value = state.message
          }
          is GenerationState.Done -> {
            isTasteGenerating = false
            _isGenerating.value = false
            _generationLog.value = PLAYLIST_CREATED_LOG
          }
          is GenerationState.Failed -> {
            isTasteGenerating = false
            _isGenerating.value = false
            _errorLog.value = state.error
          }
          is GenerationState.Idle ->
            if (_isGenerating.value) {
              isTasteGenerating = false
              _isGenerating.value = false
            }
        }
      }
    }
  }

  fun clearError() {
    _errorLog.value = null
  }

  fun resetState() {
    generateTasteJob?.cancel()
    generateTasteJob = null
    if (isTasteGenerating) {
      generationStatus.cancel()
      isTasteGenerating = false
    }
    _isGenerating.value = false
    _generationLog.value = INITIAL_LOG
    _errorLog.value = null
  }

  /**
   * Builds a playlist from locally observed listening momentum.
   *
   * @param count requested track count; clamped to a positive value by the engine.
   * @param onPlaylistCreated invoked on the caller's main dispatcher with the new playlist id.
   */
  fun generateFromTaste(count: Int = DEFAULT_TASTE_COUNT, onPlaylistCreated: (String) -> Unit) {
    if (_isGenerating.value) return
    isTasteGenerating = true
    _isGenerating.value = true
    _errorLog.value = null
    _generationLog.value = START_LOG
    generationStatus.start(START_LOG)

    generateTasteJob?.cancel()
    generateTasteJob =
      viewModelScope.launch {
        try {
          localTasteEngine.generate(count = count).fold(
            onSuccess = { playlistId ->
              isTasteGenerating = false
              _isGenerating.value = false
              _generationLog.value = PLAYLIST_CREATED_LOG
              generationStatus.succeed(playlistId)
              onPlaylistCreated(playlistId)
            },
            onFailure = { error ->
              isTasteGenerating = false
              _isGenerating.value = false
              val message = error.message.orEmpty().ifBlank { FALLBACK_ERROR }
              _errorLog.value = message
              generationStatus.fail(message)
            },
          )
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          isTasteGenerating = false
          _isGenerating.value = false
          val message = e.message ?: FALLBACK_ERROR
          _errorLog.value = message
          generationStatus.fail(message)
        }
      }
  }

  override fun onCleared() {
    generateTasteJob?.cancel()
    generateTasteJob = null
    if (isTasteGenerating) {
      generationStatus.cancel()
      isTasteGenerating = false
    }
  }

  private companion object {
    const val DEFAULT_TASTE_COUNT = 15
    const val INITIAL_LOG = "Initializing..."
    const val START_LOG = "Analyzing local listening momentum..."
    const val PLAYLIST_CREATED_LOG = "Playlist created!"
    const val FALLBACK_ERROR = "Failed to generate taste mix"
  }
}
