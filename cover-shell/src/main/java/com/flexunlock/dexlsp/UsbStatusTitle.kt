package com.flexunlock.dexlsp

internal fun usbStatusTitle(english: Boolean, function: String): String = if (english) {
    when (function) {
        "mtp" -> "Transferring files via USB"
        "ptp" -> "Transferring photos via USB"
        "rndis" -> "USB tethering"
        "midi" -> "Using USB for MIDI"
        "accessory" -> "USB accessory connected"
        else -> "USB charging"
    }
} else {
    when (function) {
        "mtp" -> "通过 USB 传输文件"
        "ptp" -> "通过 USB 传输照片"
        "rndis" -> "通过 USB 共享网络"
        "midi" -> "通过 USB 使用 MIDI"
        "accessory" -> "通过 USB 连接配件"
        else -> "USB 充电"
    }
}
