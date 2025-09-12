package com.blurr.voice.triggers.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TimePicker
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.blurr.voice.R
import com.blurr.voice.triggers.Trigger
import com.blurr.voice.triggers.TriggerManager
import com.blurr.voice.triggers.TriggerType

class CreateTriggerActivity : AppCompatActivity() {

    private lateinit var triggerManager: TriggerManager
    private lateinit var timePicker: TimePicker
    private lateinit var instructionEditText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_trigger)

        triggerManager = TriggerManager.getInstance(this)
        timePicker = findViewById(R.id.timePicker)
        instructionEditText = findViewById(R.id.instructionEditText)

        val saveButton = findViewById<Button>(R.id.saveTriggerButton)
        saveButton.setOnClickListener {
            saveTrigger()
        }
    }

    private fun saveTrigger() {
        val instruction = instructionEditText.text.toString()
        if (instruction.isBlank()) {
            Toast.makeText(this, "Instruction cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        val hour = timePicker.hour
        val minute = timePicker.minute

        val newTrigger = Trigger(
            type = TriggerType.SCHEDULED_TIME,
            hour = hour,
            minute = minute,
            instruction = instruction
        )

        triggerManager.addTrigger(newTrigger)
        Toast.makeText(this, "Trigger saved!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
