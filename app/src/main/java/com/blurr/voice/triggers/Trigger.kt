package com.blurr.voice.triggers

import java.util.UUID

enum class TriggerType {
    SCHEDULED_TIME,
    NOTIFICATION
}

data class Trigger(
    val id: String = UUID.randomUUID().toString(),
    val type: TriggerType,
    val instruction: String,
    var isEnabled: Boolean = true,
    // For SCHEDULED_TIME triggers
    val hour: Int? = null,
    val minute: Int? = null,
    // For NOTIFICATION triggers
    val packageName: String? = null,
    val appName: String? = null // For display purposes
)
