package com.example.aiscreenagent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var historyText: TextView
    private lateinit var commandInput: EditText
    private lateinit var maxInput: EditText
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        historyText = findViewById(R.id.historyText)
        commandInput = findViewById(R.id.commandInput)
        maxInput = findViewById(R.id.maxInput)

        val prefs = getSharedPreferences("agent", Context.MODE_PRIVATE)
        AgentState.maxActions = prefs.getInt("max_actions", 10)
        maxInput.setText(AgentState.maxActions.toString())

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startAgent() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopAgent("Agent stop kiya.") }
        findViewById<Button>(R.id.btnEmergency).setOnClickListener { stopAgent("EMERGENCY STOP - sab queued automation cancel.") }
        findViewById<Button>(R.id.btnRun).setOnClickListener { runCommand() }
        findViewById<Button>(R.id.btnSaveMax).setOnClickListener {
            val n = maxInput.text.toString().toIntOrNull()
            if (n == null || n !in 1..100) {
                log("Max actions 1 se 100 ke beech hona chahiye.")
            } else {
                AgentState.maxActions = n
                prefs.edit().putInt("max_actions", n).apply()
                log("Max actions per task = $n")
            }
        }
        commandInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { runCommand(); true } else false
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun startAgent() {
        if (AgentAccessibilityService.instance == null) {
            log("Pehle Accessibility Service ON karo (button 1).")
            return
        }
        AgentState.start()
        log("Agent START. Ab commands chalenge.")
    }

    private fun stopAgent(msg: String) {
        AgentState.emergencyStop()
        log(msg)
    }

    private fun runCommand() {
        val text = commandInput.text.toString()
        if (text.isBlank()) return
        log("> $text")
        commandInput.setText("")
        when (val p = CommandParser.parse(text)) {
            is Parsed.Stop -> stopAgent("Automation roka gaya.")
            is Parsed.Unknown -> log(p.hint)
            is Parsed.Act -> { AgentState.resetTask(); dispatch(p.action, false) }
        }
    }

    private fun dispatch(action: Action, confirmed: Boolean) {
        val svc = AgentAccessibilityService.instance
        if (svc == null) { log("Accessibility Service ON nahi hai."); return }
        AgentState.submit {
            val r = svc.executor.execute(action, confirmed)
            runOnUiThread { report(action, r) }
        }
    }

    private fun report(action: Action, r: ActionResult) {
        log("[${action.type}] executed=${r.executed} verified=${r.verified}\n   ${r.message}")
        if (r.needsConfirmation && action.type != "ask_confirmation") {
            AlertDialog.Builder(this)
                .setTitle("Confirm karein")
                .setMessage(r.message + "\n\nKya aap ye karne ki ijaazat dete hain?")
                .setPositiveButton("Haan, karo") { _, _ -> dispatch(action, true) }
                .setNegativeButton("Nahi", null)
                .show()
        }
    }

    private fun log(line: String) {
        AgentState.log("${timeFmt.format(Date())} $line")
        refresh()
    }

    private fun refresh() {
        val svcOn = AgentAccessibilityService.instance != null
        statusText.text = "Accessibility Service: ${if (svcOn) "ON" else "OFF"}\n" +
            "Agent: ${if (AgentState.active) "RUNNING" else "STOPPED"}\n" +
            "Screen monitoring: OFF (Phase 2 mein aayega)\n" +
            "Max actions/task: ${AgentState.maxActions}"
        historyText.text = AgentState.history.reversed().joinToString("\n")
    }
}
