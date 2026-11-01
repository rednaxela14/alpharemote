package org.staacks.alpharemote.camera

import android.os.SystemClock

sealed class CameraState

class CameraStateGone : CameraState()
class CameraStateConnecting : CameraState()

class CameraStateNotBonded : CameraState()
class CameraStateRemoteDisabled : CameraState()

data class CameraStateIdentified(
    val name: String,
    val address: String
) : CameraState()

class ReportedBoolean() {
    var lastChange: Long? = null
    var state = false
        set(value) {
            field = value
            lastChange = SystemClock.elapsedRealtime()
        }
    constructor(state: Boolean) : this() {
        this.state = state
    }
}

data class CameraStateReady(
    val name: String?,
    val focus: ReportedBoolean,
    val shutter: ReportedBoolean,
    val recording: ReportedBoolean,
    val pressedButtons: Set<ButtonCode>,
    val pressedJogs: Set<JogCode>,
    val exposureCount: Int = 0 //Counts reported exposure starts, so even exposures shorter than a state update are not missed
) : CameraState()

data class CameraStateError(
    val exception: Exception?,
    val description: String = ""
) : CameraState()
