package com.blurr.voice.triggers

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import java.util.Calendar

class TriggerManager(private val context: Context) {

    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
    private val gson = Gson()

    fun addTrigger(trigger: Trigger) {
        val triggers = loadTriggers()
        triggers.add(trigger)
        saveTriggers(triggers)
        if (trigger.isEnabled) {
            scheduleAlarm(trigger)
        }
    }

    fun removeTrigger(trigger: Trigger) {
        val triggers = loadTriggers()
        val triggerToRemove = triggers.find { it.id == trigger.id }
        if (triggerToRemove != null) {
            cancelAlarm(triggerToRemove)
            triggers.remove(triggerToRemove)
            saveTriggers(triggers)
        }
    }

    fun getTriggers(): List<Trigger> {
        return loadTriggers()
    }

    fun updateTrigger(trigger: Trigger) {
        val triggers = loadTriggers()
        val index = triggers.indexOfFirst { it.id == trigger.id }
        if (index != -1) {
            triggers[index] = trigger
            saveTriggers(triggers)
            if (trigger.isEnabled) {
                scheduleAlarm(trigger)
            } else {
                cancelAlarm(trigger)
            }
        }
    }

    private fun scheduleAlarm(trigger: Trigger) {
        val intent = Intent(context, TriggerReceiver::class.java).apply {
            action = TriggerReceiver.ACTION_EXECUTE_TASK
            putExtra(TriggerReceiver.EXTRA_TASK_INSTRUCTION, trigger.instruction)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            trigger.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, trigger.hour)
            set(Calendar.MINUTE, trigger.minute)
            set(Calendar.SECOND, 0)
        }

        // If the trigger time today has already passed, schedule it for tomorrow
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
    }

    private fun cancelAlarm(trigger: Trigger) {
        val intent = Intent(context, TriggerReceiver::class.java).apply {
            action = TriggerReceiver.ACTION_EXECUTE_TASK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            trigger.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun saveTriggers(triggers: List<Trigger>) {
        val json = gson.toJson(triggers)
        sharedPreferences.edit().putString(KEY_TRIGGERS, json).apply()
    }

    private fun loadTriggers(): MutableList<Trigger> {
        val json = sharedPreferences.getString(KEY_TRIGGERS, null)
        return if (json != null) {
            val type = object : TypeToken<MutableList<Trigger>>() {}.type
            gson.fromJson(json, type)
        } else {
            mutableListOf()
        }
    }

    companion object {
        private const val PREFS_NAME = "com.blurr.voice.triggers.prefs"
        private const val KEY_TRIGGERS = "triggers_list"

        @Volatile
        private var INSTANCE: TriggerManager? = null

        fun getInstance(context: Context): TriggerManager {
            return INSTANCE ?: synchronized(this) {
                val instance = TriggerManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
