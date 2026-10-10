package com.flexunlock.dexlsp

import android.app.Notification

internal fun notificationFlagsAllowDismissal(clearable: Boolean, flags: Int): Boolean =
    clearable && flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR) == 0
