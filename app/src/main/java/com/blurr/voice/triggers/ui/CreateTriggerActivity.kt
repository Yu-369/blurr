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

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TimePicker
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.R
import com.blurr.voice.triggers.Trigger
import com.blurr.voice.triggers.TriggerManager
import com.blurr.voice.triggers.TriggerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CreateTriggerActivity : AppCompatActivity() {

    private lateinit var triggerManager: TriggerManager
    private lateinit var instructionEditText: EditText
    private lateinit var triggerTypeRadioGroup: RadioGroup
    private lateinit var scheduledTimeOptions: LinearLayout
    private lateinit var notificationOptions: LinearLayout
    private lateinit var timePicker: TimePicker
    private lateinit var appsRecyclerView: RecyclerView
    private lateinit var appAdapter: AppAdapter

    private var selectedTriggerType = TriggerType.SCHEDULED_TIME
    private var selectedApp: AppInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_trigger)

        triggerManager = TriggerManager.getInstance(this)
        instructionEditText = findViewById(R.id.instructionEditText)
        triggerTypeRadioGroup = findViewById(R.id.triggerTypeRadioGroup)
        scheduledTimeOptions = findViewById(R.id.scheduledTimeOptions)
        notificationOptions = findViewById(R.id.notificationOptions)
        timePicker = findViewById(R.id.timePicker)
        appsRecyclerView = findViewById(R.id.appsRecyclerView)

        setupRadioGroup()
        setupRecyclerView()
        loadApps()

        val saveButton = findViewById<Button>(R.id.saveTriggerButton)
        saveButton.setOnClickListener {
            saveTrigger()
        }
    }

    private fun setupRadioGroup() {
        triggerTypeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.scheduledTimeRadioButton -> {
                    selectedTriggerType = TriggerType.SCHEDULED_TIME
                    scheduledTimeOptions.visibility = View.VISIBLE
                    notificationOptions.visibility = View.GONE
                }
                R.id.notificationRadioButton -> {
                    selectedTriggerType = TriggerType.NOTIFICATION
                    scheduledTimeOptions.visibility = View.GONE
                    notificationOptions.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupRecyclerView() {
        appsRecyclerView.layoutManager = LinearLayoutManager(this)
        appAdapter = AppAdapter(emptyList()) { app ->
            selectedApp = app
        }
        appsRecyclerView.adapter = appAdapter
    }

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map {
                    AppInfo(
                        appName = it.loadLabel(pm).toString(),
                        packageName = it.packageName,
                        icon = it.loadIcon(pm)
                    )
                }
                .sortedBy { it.appName }

            withContext(Dispatchers.Main) {
                appAdapter = AppAdapter(apps) { app ->
                    selectedApp = app
                }
                appsRecyclerView.adapter = appAdapter
            }
        }
    }

    private fun saveTrigger() {
        val instruction = instructionEditText.text.toString()
        if (instruction.isBlank()) {
            Toast.makeText(this, "Instruction cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        val trigger = when (selectedTriggerType) {
            TriggerType.SCHEDULED_TIME -> {
                if (!com.blurr.voice.triggers.PermissionUtils.canScheduleExactAlarms(this)) {
                    showExactAlarmPermissionDialog()
                    return
                }
                Trigger(
                    type = TriggerType.SCHEDULED_TIME,
                    hour = timePicker.hour,
                    minute = timePicker.minute,
                    instruction = instruction
                )
            }
            TriggerType.NOTIFICATION -> {
                if (selectedApp == null) {
                    Toast.makeText(this, "Please select an app", Toast.LENGTH_SHORT).show()
                    return
                }
                Trigger(
                    type = TriggerType.NOTIFICATION,
                    packageName = selectedApp!!.packageName,
                    appName = selectedApp!!.appName,
                    instruction = instruction
                )
            }
        }

        triggerManager.addTrigger(trigger)
        Toast.makeText(this, "Trigger saved!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun showExactAlarmPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("To schedule tasks at a precise time, Panda needs the 'Alarms & Reminders' permission. Please grant this in the next screen.")
            .setPositiveButton("Grant Permission") { _, _ ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
