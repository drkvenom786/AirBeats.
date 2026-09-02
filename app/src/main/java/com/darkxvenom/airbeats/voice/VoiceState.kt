package com.darkxvenom.airbeats.voice

/**
 * Single authoritative state machine for the AirBeats Voice Assistant.
 */
enum class VoiceState {
    DISABLED,
    INITIALIZING,
    WAKE_WORD,
    COMMAND_LISTEN,
    EXECUTING
}

/**
 * Deterministic, strongly-typed music commands.
 */
enum class AirBeatsCommand {
    PLAY,
    PAUSE,
    RESUME,
    NEXT,
    PREVIOUS,
    VOLUME_UP,
    VOLUME_DOWN,
    MUTE,
    UNMUTE,
    TOGGLE_LIKE,
    START_RADIO,
    PLAY_SONG,
    PLAY_GENERIC,
    PLAY_CACHED,
    PLAY_LIKED,
    NONE
}

/**
 * Thread-safe decoupled voice events emitted to UI and playback layers.
 */
sealed interface VoiceEvent {
    data object WakeWordDetected : VoiceEvent
    data class CommandDetected(val command: AirBeatsCommand, val query: String? = null, val rawText: String? = null) : VoiceEvent
    data class RmsChanged(val rmsDb: Float) : VoiceEvent
    data class StateChanged(val newState: VoiceState) : VoiceEvent
    data class Error(val message: String) : VoiceEvent
}
