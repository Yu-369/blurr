package com.blurr.voice.triggers

import java.util.UUID

enum class TriggerType {
    SCHEDULED_TIME
}

data class Trigger(
    val id: String = UUID.randomUUID().toString(),
    val type: TriggerType,
    val hour: Int,
    val minute: Int,
    val instruction: String,
    var isEnabled: Boolean = true
)
