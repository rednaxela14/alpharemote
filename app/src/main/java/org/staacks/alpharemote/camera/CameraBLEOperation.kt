package org.staacks.alpharemote.camera

import android.bluetooth.BluetoothGattCharacteristic

sealed class CameraBLEOperation

data class CameraBLEWrite(
    val characteristic: BluetoothGattCharacteristic,
    val data: ByteArray,
    val completeCallback: ((Int) -> Unit)? = null
) : CameraBLEOperation()

data class CameraBLERead(
    val characteristic: BluetoothGattCharacteristic,
    val resultCallback: (Int, ByteArray) -> Unit
) : CameraBLEOperation()

data class CameraBLESubscribe(
    val characteristic: BluetoothGattCharacteristic
) : CameraBLEOperation()