package org.staacks.alpharemote.service

import android.Manifest
import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import org.staacks.alpharemote.MainActivity
import org.staacks.alpharemote.R
import org.staacks.alpharemote.SettingsStore
import org.staacks.alpharemote.camera.ButtonCode
import org.staacks.alpharemote.camera.CAButton
import org.staacks.alpharemote.camera.CACountdown
import org.staacks.alpharemote.camera.CAJog
import org.staacks.alpharemote.camera.CAWaitFor
import org.staacks.alpharemote.camera.CameraAction
import org.staacks.alpharemote.camera.CameraActionStep
import org.staacks.alpharemote.camera.CameraBLE
import org.staacks.alpharemote.camera.CameraStateIdentified
import org.staacks.alpharemote.camera.CameraStateReady
import org.staacks.alpharemote.camera.WaitTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.staacks.alpharemote.camera.CameraActionPreset
import org.staacks.alpharemote.camera.JogCode
import org.staacks.alpharemote.ui.settings.CompanionDeviceHelper
import java.io.Serializable
import java.util.LinkedList
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong


class AlphaRemoteService : CompanionDeviceService() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var timer: TimerTask? = null
    private var notificationUI: NotificationUI? = null

    private lateinit var pendingActionsWakeLock: PowerManager.WakeLock

    companion object {

        private var cameraBLE: CameraBLE? = null
        private var deviceAppearedCount = 0

        private val _serviceState = MutableStateFlow<ServiceState>(ServiceStateGone())
        val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

        fun disconnect() {
            cameraBLE?.disconnectFromDevice()
        }

        fun sendCameraAction(context: Context, cameraAction: CameraAction, down: Boolean = true, up: Boolean = true) {
            if (serviceState.value !is ServiceRunning)
                return
            val intent = Intent(context, AlphaRemoteService::class.java).apply {
                action = BUTTON_INTENT_ACTION
                putExtra(BUTTON_INTENT_CAMERA_ACTION_EXTRA, cameraAction as Serializable)
                putExtra(BUTTON_INTENT_CAMERA_ACTION_DOWN_EXTRA, down)
                putExtra(BUTTON_INTENT_CAMERA_ACTION_UP_EXTRA, up)
            }
            context.startService(intent)
        }

        const val BUTTON_INTENT_ACTION = "NOTIFICATION_BUTTON"
        const val BUTTON_INTENT_CAMERA_ACTION_EXTRA = "camera_action"
        const val BUTTON_INTENT_CAMERA_ACTION_DOWN_EXTRA = "down"
        const val BUTTON_INTENT_CAMERA_ACTION_UP_EXTRA = "up"

        const val BRACKET_SEQUENCE_INTENT_ACTION = "BRACKET_SEQUENCE"
        const val BRACKET_COUNT_EXTRA = "br_count"
        const val BRACKET_STEP_EXTRA = "br_step"
        const val BRACKET_SETTLE_EXTRA = "br_settle"
        const val BRACKET_DIR_EXTRA = "br_dir"
        const val BRACKET_SPEED_EXTRA = "br_speed"
        const val BRACKET_APPROACH_EXTRA = "br_approach"

        const val ADVANCED_SEQUENCE_INTENT_ACTION = "ADVANCED_SEQUENCE"
        const val ADVANCED_SEQUENCE_INTENT_BULB_DURATION_EXTRA = "duration"
        const val ADVANCED_SEQUENCE_INTENT_INTERVAL_DURATION_EXTRA = "interval"
        const val ADVANCED_SEQUENCE_INTENT_INTERVAL_COUNT_EXTRA = "count"
        const val ADVANCED_SEQUENCE_INTENT_FOCUS_BRACKETING_AMOUNT_EXTRA = "focus_step"

        private var pendingActionSteps = LinkedList<CameraActionStep>()
        var broadcastControl = false
    }

    override fun onCreate() {
        super.onCreate()
        pendingActionsWakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlphaRemoteService::PendingActionsWakeLock")
        }
    }

    override fun onDeviceAppeared(address: String) {
        Log.d(MainActivity.TAG, "Device appeared: $address")
        try {
            super.onDeviceAppeared(address) //This is abstract on Android 12
        } catch (_: AbstractMethodError) {}

        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w(MainActivity.TAG, "Missing Bluetooth permission. Launching activity instead.")
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.NAVIGATE_TO_INTENT_EXTRA, R.id.navigation_settings)
            }
            startActivity(intent)
            stopSelf()
            return
        }

        val settingsStore = SettingsStore(application)
        notificationUI = notificationUI ?: (NotificationUI(applicationContext).also { notificationUI ->
            scope.launch {
                settingsStore.customButtonSettings.stateIn(
                    scope = this,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = SettingsStore.CustomButtonSettings(null, 1.0f)
                ).collectLatest {
                    notificationUI.updateCustomButtons(it.customButtonList, it.scale)
                }
            }
            scope.launch {
                settingsStore.permissions.collectLatest {
                    if (it.notification) //Refresh notification if notification permission has been granted after it was not granted previously
                        notificationUI.updateNotification()
                    broadcastControl = it.broadcastControl
                }
            }
        })

        if (cameraBLE == null) {
            cancelPendingActionSteps()
            deviceAppearedCount = 1
            cameraBLE = CameraBLE(scope, application, address, ::onConnect, ::onDisconnect).apply {
                scope.launch {
                    cameraState.collect {
                        when (it) {
                            is CameraStateReady -> checkWaitAction(it)
                            is CameraStateIdentified -> settingsStore.setCameraId(it.name, it.address)
                            else -> cancelPendingActionSteps()
                        }
                    }
                }
                scope.launch {
                    cameraState.collectLatest { cameraState ->
                        _serviceState.update {
                            (it as? ServiceRunning)?.copy(cameraState = cameraState) ?: ServiceRunning(cameraState, null, null)
                        }
                        notificationUI?.onCameraStateUpdate(cameraState)
                    }
                }
            }
        } else {
            Log.w(MainActivity.TAG, "onDeviceAppeared ignored as cameraBLE has already been instantiated.")
            deviceAppearedCount++
            return
        }
    }

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        super.onDeviceAppeared(associationInfo)
        Log.d(MainActivity.TAG, "API33 onDeviceAppeared: $associationInfo")
    }

    override fun onDeviceDisappeared(address: String) {
        Log.d(MainActivity.TAG, "Device disappeared: $address")
        try {
            super.onDeviceDisappeared(address) //This is abstract on Android 12
        } catch (_: AbstractMethodError) {}
        deviceAppearedCount--

        if (deviceAppearedCount == 0) {
            cameraBLE?.disconnectFromDevice()
            cameraBLE = null
            if (pendingActionsWakeLock.isHeld) {
                pendingActionsWakeLock.release()
            }
            job.cancelChildren()
            stopSelf()
        }
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        super.onDeviceDisappeared(associationInfo)
        Log.d(MainActivity.TAG, "API33 onDeviceDisappeared: $associationInfo")
    }

    private fun onConnect() {
        Log.d(MainActivity.TAG, "onConnect")
        notificationUI?.let {
            startForeground(
                it.notificationId,
                it.start(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            )
        }
    }

    private fun onDisconnect() {
        Log.d(MainActivity.TAG, "onDisconnect")
        _serviceState.value = ServiceStateGone()
        cancelPendingActionSteps()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationUI?.stop()
        cameraBLE = null
    }

    private fun executeCameraAction(cameraAction: CameraAction, down: Boolean, up: Boolean) {
        var translatedUp = up
        var translatedDown = down

        // Translate toggle release to down or up depending on button state
        if (cameraAction.toggle) {
            translatedDown = false // Toggle only acts on button release. Do not pass through down events
            if (translatedUp) {
                ((serviceState.value as? ServiceRunning)?.cameraState as? CameraStateReady)?.let { cameraState ->
                    translatedUp = cameraAction.preset.template.referenceButton in cameraState.pressedButtons
                    translatedDown = !translatedUp
                }
            }
        }

        if (translatedDown && translatedUp) //Simple click, i.e. button in notification area
            startCameraAction(cameraAction.getClickStepList(this))
        else if (translatedDown) //Button released
            startCameraAction(cameraAction.getPressStepList(this))
        else if (translatedUp) //Button pressed
            startCameraAction(cameraAction.getReleaseStepList())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(MainActivity.TAG, "onStartCommand: $intent")
        when (intent?.action) {
            BUTTON_INTENT_ACTION -> {
                val cameraAction = intent.getSerializableExtra(BUTTON_INTENT_CAMERA_ACTION_EXTRA) as CameraAction
                val down = intent.getBooleanExtra(BUTTON_INTENT_CAMERA_ACTION_DOWN_EXTRA, true)
                val up = intent.getBooleanExtra(BUTTON_INTENT_CAMERA_ACTION_UP_EXTRA, true)

                executeCameraAction(cameraAction, down, up)
            }
            BRACKET_SEQUENCE_INTENT_ACTION -> {
                val count = intent.getIntExtra(BRACKET_COUNT_EXTRA, 2)
                val stepS = intent.getFloatExtra(BRACKET_STEP_EXTRA, 0.3f)
                val settleS = intent.getFloatExtra(BRACKET_SETTLE_EXTRA, 0.6f)
                val dir = intent.getIntExtra(BRACKET_DIR_EXTRA, 1)
                val approach = intent.getFloatExtra(BRACKET_APPROACH_EXTRA, 0f)
                val speed = intent.getFloatExtra(BRACKET_SPEED_EXTRA, 0.08f)
                val sb = (speed * 127f).roundToInt().coerceIn(1, 127).toByte()
                val label = getString(R.string.camera_advanced_focus_bracketing_timer_label)
                val steps: MutableList<CameraActionStep> = mutableListOf()
                if (kotlin.math.abs(approach) > 0.01f) {
                    val j = if (approach > 0) JogCode.FOCUS_FAR else JogCode.FOCUS_NEAR
                    steps += CAJog(true, sb, j)
                    steps += CACountdown(label, kotlin.math.abs(approach))
                    steps += CAJog(false, sb, j)
                    steps += CACountdown(label, settleS)
                }
                val jog = if (dir > 0) JogCode.FOCUS_FAR else JogCode.FOCUS_NEAR
                for (i in 0 until count) {
                    steps += CAButton(pressed = true, ButtonCode.SHUTTER_HALF)
                    steps += CAButton(pressed = true, ButtonCode.SHUTTER_FULL, isSequenceTrigger = true)
                    steps += CAButton(pressed = false, ButtonCode.SHUTTER_FULL)
                    steps += CAButton(pressed = false, ButtonCode.SHUTTER_HALF)
                    if (i < count - 1) {
                        steps += CAWaitFor(WaitTarget.SHUTTER, true)
                        steps += CAButton(pressed = true, ButtonCode.SHUTTER_HALF)
                        steps += CAButton(pressed = false, ButtonCode.SHUTTER_HALF)
                        steps += CAJog(true, sb, jog)
                        steps += CACountdown(label, stepS)
                        steps += CAJog(false, sb, jog)
                        steps += CACountdown(label, settleS)
                    }
                }
                startCameraAction(steps)
            }
            ADVANCED_SEQUENCE_INTENT_ACTION -> {
                val bulbDuration = intent.getSerializableExtra(ADVANCED_SEQUENCE_INTENT_BULB_DURATION_EXTRA) as Float
                val intervalDuration = intent.getSerializableExtra(ADVANCED_SEQUENCE_INTENT_INTERVAL_DURATION_EXTRA) as Float
                val intervalCount = intent.getSerializableExtra(ADVANCED_SEQUENCE_INTENT_INTERVAL_COUNT_EXTRA) as Int
                val focusBracketingAmount = intent.getSerializableExtra(ADVANCED_SEQUENCE_INTENT_FOCUS_BRACKETING_AMOUNT_EXTRA) as Float

                val stepSequencePrepare: MutableList<CameraActionStep> = mutableListOf()
                stepSequencePrepare += CAButton(pressed = true, ButtonCode.SHUTTER_HALF)
                if (intervalDuration > 0) {
                    stepSequencePrepare += CACountdown(getString(R.string.camera_advanced_interval_timer_label), intervalDuration)
                }

                val stepSequenceTrigger: MutableList<CameraActionStep> = mutableListOf()
                stepSequenceTrigger += CAButton(pressed = true, ButtonCode.SHUTTER_FULL, isSequenceTrigger = true)
                if (bulbDuration > 0) {
                    stepSequenceTrigger += CAWaitFor(WaitTarget.SHUTTER)
                }
                stepSequenceTrigger += CAButton(pressed = false, ButtonCode.SHUTTER_FULL)
                stepSequenceTrigger += CAButton(pressed = false, ButtonCode.SHUTTER_HALF)
                if (bulbDuration > 0) {
                    stepSequenceTrigger += listOf(
                        CACountdown(getString(R.string.camera_advanced_bulb_timer_label), bulbDuration),
                        CAButton(pressed = true, ButtonCode.SHUTTER_HALF),
                        CAButton(pressed = true, ButtonCode.SHUTTER_FULL),
                        CAButton(pressed = false, ButtonCode.SHUTTER_FULL),
                        CAButton(pressed = false, ButtonCode.SHUTTER_HALF),
                    )
                }

                val focusSequence = if (focusBracketingAmount > 0) {
                    listOf(
                        CAWaitFor(WaitTarget.SHUTTER, true), //Wait for the exposure to finish
                        CAButton(pressed = true, ButtonCode.SHUTTER_HALF), //Dismiss immediate preview by half pressing shutter
                        CAButton(pressed = false, ButtonCode.SHUTTER_HALF),
                        CAJog(true, JogCode.FOCUS_FAR.maxStep, JogCode.FOCUS_FAR),
                        CACountdown(getString(R.string.camera_advanced_focus_bracketing_timer_label), focusBracketingAmount),
                        CAJog(false, JogCode.FOCUS_FAR.maxStep, JogCode.FOCUS_FAR)
                    )
                } else listOf()

                startCameraAction(
                    stepSequencePrepare + stepSequenceTrigger
                            + List(max(intervalCount-1, 0)) {stepSequencePrepare + focusSequence + stepSequenceTrigger}.flatten()
                )
            }
        }

        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scope.launch {
            SettingsStore(application).getCustomButtonList().let { customButtonList ->
                SettingsStore(application).getNotificationButtonSize()?.let { notificationButtonSize ->
                    notificationUI?.updateCustomButtons(customButtonList, notificationButtonSize)
                }
            }
        }
    }

    @Synchronized
    fun cancelPendingActionSteps(): Boolean {
        var pendingStepsCancelled = false
        timer?.cancel()
        if (pendingActionSteps.isNotEmpty()) {
            pendingActionSteps.clear()
            for (button in ButtonCode.entries) {
                cameraBLE?.executeCameraActionStep(CAButton(false, button))
            }
            for (jog in JogCode.entries) {
                cameraBLE?.executeCameraActionStep(CAJog(false, -1, jog))
            }
            pendingStepsCancelled = true
            updatePendingActionStatistics()
        }
        _serviceState.update {
            (it as? ServiceRunning)?.copy(countdown = null, countdownLabel = null) ?: it
        }
        notificationUI?.hideCountdown()
        if (pendingActionsWakeLock.isHeld) {
            pendingActionsWakeLock.release()
        }
        return pendingStepsCancelled
    }

    private fun isLongRunningSequence(steps: List<CameraActionStep>): Boolean {
        for (step in steps) {
            when (step) {
                is CACountdown -> return true
                is CAWaitFor -> return true
                else -> {}
            }
        }
        return false
    }

    @Synchronized
    @SuppressLint("WakelockTimeout")
    fun startCameraAction(steps: List<CameraActionStep>) {
        if (cancelPendingActionSteps() && isLongRunningSequence(steps))
            return //If this is more than a simple button press and there were pending action, this button press is only used as a cancellation of the previous sequence
        pendingActionSteps.addAll(steps)
        if (!pendingActionsWakeLock.isHeld) {
            pendingActionsWakeLock.acquire()
        }
        executeNextCameraActionStep()
    }

    @Synchronized
    fun updatePendingActionStatistics() {
        var pendingTriggerCount = 0
        for (actionStep in pendingActionSteps) {
            if (actionStep is CAButton && actionStep.isSequenceTrigger)
                pendingTriggerCount++
        }
        if (pendingActionSteps.isEmpty() && pendingActionsWakeLock.isHeld) {
            pendingActionsWakeLock.release()
        }
        _serviceState.update {
            (it as? ServiceRunning)?.copy(pendingTriggerCount = pendingTriggerCount) ?: it
        }
    }

    @Synchronized
    fun executeNextCameraActionStep() {
        updatePendingActionStatistics()
        while ((pendingActionSteps.peek() is CAButton || pendingActionSteps.peek() is CAJog)) {
            pendingActionSteps.poll()?.let {
                updatePendingActionStatistics()
                cameraBLE?.executeCameraActionStep(it)
            }
        }
        (pendingActionSteps.peek() as? CACountdown)?.let { step ->
            val time = (step.duration * 1000).roundToLong()
            timer?.cancel()
            timer = Timer().schedule(time) {
                countdownActionComplete()
            }
            val targetTime = SystemClock.elapsedRealtime() + time
            _serviceState.update {
                (it as? ServiceRunning)?.copy(countdown = targetTime, countdownLabel = step.label) ?: it
            }
            notificationUI?.showCountdown(targetTime, step.label)
            return
        }
        (serviceState.value as? ServiceRunning)?.let {
            (it.cameraState as? CameraStateReady)?.let { cameraState ->
                checkWaitAction(cameraState)
            }
        }
    }

    @Synchronized
    fun countdownActionComplete() {
        _serviceState.update {
            (it as? ServiceRunning)?.copy(countdown = null, countdownLabel = null) ?: it
        }
        notificationUI?.hideCountdown()
        val nextAction = pendingActionSteps.peek()
        if (nextAction is CACountdown) {
            pendingActionSteps.removeFirst()
            executeNextCameraActionStep()
        }
    }

    @Synchronized
    fun checkWaitAction(state: CameraStateReady) {
        val nextAction = pendingActionSteps.peek()
        if (nextAction is CAWaitFor) {
            when (nextAction.target) {
                WaitTarget.FOCUS -> if (state.focus.state != nextAction.invert) {
                    pendingActionSteps.removeFirst()
                    executeNextCameraActionStep()
                }
                WaitTarget.SHUTTER -> if (state.shutter.state != nextAction.invert) {
                    pendingActionSteps.removeFirst()
                    executeNextCameraActionStep()
                }
                WaitTarget.RECORDING -> if (state.recording.state != nextAction.invert) {
                    pendingActionSteps.removeFirst()
                    executeNextCameraActionStep()
                }
            }
        }
    }

}