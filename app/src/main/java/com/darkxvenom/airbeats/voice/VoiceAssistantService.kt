package com.darkxvenom.airbeats.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import com.darkxvenom.airbeats.MainActivity
import com.darkxvenom.airbeats.R
import com.darkxvenom.airbeats.constants.EnableVoiceAssistantKey
import com.darkxvenom.airbeats.playback.MusicService
import com.darkxvenom.airbeats.utils.dataStore
import com.darkxvenom.airbeats.utils.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

class VoiceAssistantService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: PowerManager.WakeLock? = null
    private var musicService: MusicService? = null
    private var isMusicServiceBound = false

    private var audioCapture: VoiceAudioCapture? = null
    private var inferenceManager: VoiceInferenceManager? = null
    private var stateMachine: VoiceStateMachine? = null
    private lateinit var actionExecutor: VoiceAssistantActionExecutor
    private var overlayManager: VoiceAssistantOverlayManager? = null

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "voice_assistant_channel"
        const val ACTION_STOP_VOICE_ASSISTANT = "com.darkxvenom.airbeats.voice.STOP"
        const val ACTION_TRIGGER_LISTEN = "com.darkxvenom.airbeats.voice.LISTEN"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        @Volatile
        var instance: VoiceAssistantService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, VoiceAssistantService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceAssistantService::class.java)
            context.stopService(intent)
        }
    }

    private val musicServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder is MusicService.MusicBinder) {
                musicService = binder.service
                Timber.d("VoiceAssistantService connected to MusicService")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            Timber.d("VoiceAssistantService disconnected from MusicService")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        _isServiceRunning.value = true

        acquireWakeLock()
        createNotificationChannel()
        startAsForeground()
        bindMusicService()

        overlayManager = VoiceAssistantOverlayManager(this)

        actionExecutor = VoiceAssistantActionExecutor(
            context = this,
            scope = serviceScope,
            getMusicService = { musicService },
            overlayManager = overlayManager
        )

        val infManager = VoiceInferenceManager(this, serviceScope)
        inferenceManager = infManager

        val stateMach = VoiceStateMachine(serviceScope) { newState ->
            when (newState) {
                VoiceState.COMMAND_LISTEN -> {
                    infManager.startCommandListening()
                    overlayManager?.showListening()
                    overlayManager?.updateSpokenText("Listening...")
                }
                VoiceState.EXECUTING -> {
                    // Kept visible for confirmation toast/label
                }
                VoiceState.WAKE_WORD, VoiceState.DISABLED, VoiceState.INITIALIZING -> {
                    overlayManager?.hideOverlay()
                }
            }
        }
        stateMachine = stateMach

        // Single AudioRecord Owner
        audioCapture = VoiceAudioCapture(
            onAudioFrame = { buffer, count ->
                infManager.onAudioFrame(buffer, count, stateMach.state.value)
            },
            onRmsCalculated = { db ->
                overlayManager?.updateAudioRms(db)
            }
        )

        // Observe Decoupled Voice Events
        serviceScope.launch {
            infManager.events.collectLatest { event ->
                when (event) {
                    is VoiceEvent.WakeWordDetected -> {
                        Timber.i("VoiceAssistantService: Wake Word event received")
                        stateMach.onWakeWordDetected()
                    }
                    is VoiceEvent.CommandDetected -> {
                        stateMach.onCommandDetected(event.command)
                        executeAirBeatsCommand(event.command, event.query, event.rawText)
                    }
                    is VoiceEvent.RmsChanged -> {
                        overlayManager?.updateAudioRms(event.rmsDb)
                    }
                    is VoiceEvent.StateChanged -> {}
                    is VoiceEvent.Error -> {
                        Timber.e("VoiceAssistantService Error: %s", event.message)
                    }
                }
            }
        }

        // Transition to active wake word listening
        stateMach.transitionTo(VoiceState.WAKE_WORD)
        audioCapture?.start()
        Timber.i("VoiceAssistantService initialized and listening with single AudioRecord capture loop")
    }

    private fun executeAirBeatsCommand(command: AirBeatsCommand, query: String?, rawText: String?) {
        val label = rawText ?: command.name
        overlayManager?.updateSpokenText(label)
        updateNotificationText("Executing: \"$label\"")

        val voiceCommand: VoiceCommand = when (command) {
            AirBeatsCommand.NEXT -> VoiceCommand.NextTrack
            AirBeatsCommand.PREVIOUS -> VoiceCommand.PreviousTrack
            AirBeatsCommand.PAUSE -> VoiceCommand.Pause
            AirBeatsCommand.RESUME, AirBeatsCommand.PLAY -> VoiceCommand.Resume
            AirBeatsCommand.VOLUME_UP -> VoiceCommand.VolumeUp
            AirBeatsCommand.VOLUME_DOWN -> VoiceCommand.VolumeDown
            AirBeatsCommand.MUTE -> VoiceCommand.Mute
            AirBeatsCommand.UNMUTE -> VoiceCommand.Unmute
            AirBeatsCommand.TOGGLE_LIKE -> VoiceCommand.ToggleLike
            AirBeatsCommand.START_RADIO -> VoiceCommand.StartRadio
            AirBeatsCommand.PLAY_GENERIC -> VoiceCommand.PlayGenericMusic
            AirBeatsCommand.PLAY_CACHED -> VoiceCommand.PlayCachedSongs
            AirBeatsCommand.PLAY_LIKED -> VoiceCommand.PlayLikedSongs
            AirBeatsCommand.PLAY_SONG -> VoiceCommand.PlaySong(query ?: "Music")
            AirBeatsCommand.NONE -> return
        }

        actionExecutor.execute(voiceCommand)
    }

    fun triggerListening() {
        stateMachine?.transitionTo(VoiceState.COMMAND_LISTEN)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VOICE_ASSISTANT -> {
                Timber.i("Stopping VoiceAssistantService via notification action")
                serviceScope.launch {
                    dataStore.edit { preferences ->
                        preferences[EnableVoiceAssistantKey] = false
                    }
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_LISTEN -> {
                triggerListening()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val notification = buildForegroundNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error starting VoiceAssistantService as foreground")
        }
    }

    private fun buildForegroundNotification(statusText: String? = null): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val listenIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, VoiceAssistantService::class.java).apply {
                action = ACTION_TRIGGER_LISTEN
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceAssistantService::class.java).apply {
                action = ACTION_STOP_VOICE_ASSISTANT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = statusText ?: getString(R.string.voice_notification_text)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.airbeats_monochrome)
            .setContentTitle(getString(R.string.voice_notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.mic,
                "Speak",
                listenIntent
            )
            .addAction(
                R.drawable.airbeats_monochrome,
                getString(R.string.voice_notification_stop),
                stopIntent
            )
            .build()
    }

    fun updateNotificationText(statusText: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildForegroundNotification(statusText))

            // Revert back to default status after 4 seconds
            serviceScope.launch {
                delay(4000)
                notificationManager.notify(NOTIFICATION_ID, buildForegroundNotification(null))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating notification text")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.voice_assistant_desc)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AirBeats:VoiceAssistantWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 12 hours max safety limit
            }
        } catch (e: Exception) {
            Timber.e(e, "Error acquiring wake lock")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Timber.e(e, "Error releasing wake lock")
        }
    }

    private fun bindMusicService() {
        try {
            val intent = Intent(this, MusicService::class.java)
            startService(intent)
            isMusicServiceBound = bindService(intent, musicServiceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Timber.e(e, "Error binding to MusicService")
        }
    }

    private fun unbindMusicService() {
        if (isMusicServiceBound) {
            try {
                unbindService(musicServiceConnection)
                isMusicServiceBound = false
            } catch (e: Exception) {
                Timber.e(e, "Error unbinding from MusicService")
            }
        }
    }

    override fun onDestroy() {
        _isServiceRunning.value = false
        instance = null

        overlayManager?.hide()
        overlayManager = null

        audioCapture?.stop()
        audioCapture = null

        inferenceManager?.release()
        inferenceManager = null

        stateMachine?.reset()
        stateMachine = null

        actionExecutor.release()
        releaseWakeLock()
        unbindMusicService()
        serviceScope.cancel()

        super.onDestroy()
        Timber.d("VoiceAssistantService destroyed cleanly")
    }
}
