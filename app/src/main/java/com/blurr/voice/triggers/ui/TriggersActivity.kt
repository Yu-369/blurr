package com.blurr.voice.triggers.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.R
import com.blurr.voice.triggers.TriggerManager
import com.google.android.material.floatingactionbutton.FloatingActionButton

class TriggersActivity : AppCompatActivity() {

    private lateinit var triggerManager: TriggerManager
    private lateinit var triggerAdapter: TriggerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_triggers)

        triggerManager = TriggerManager.getInstance(this)

        setupRecyclerView()
        setupFab()
    }

    override fun onResume() {
        super.onResume()
        loadTriggers()
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.triggersRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        triggerAdapter = TriggerAdapter(mutableListOf()) { trigger, isEnabled ->
            trigger.isEnabled = isEnabled
            triggerManager.updateTrigger(trigger)
        }
        recyclerView.adapter = triggerAdapter
    }

    private fun setupFab() {
        val fab = findViewById<FloatingActionButton>(R.id.addTriggerFab)
        fab.setOnClickListener {
            startActivity(Intent(this, CreateTriggerActivity::class.java))
        }
    }

    private fun loadTriggers() {
        val triggers = triggerManager.getTriggers()
        triggerAdapter.updateTriggers(triggers)
    }
}
