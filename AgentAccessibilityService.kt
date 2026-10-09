package com.example.aiscreenagent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class AgentAccessibilityService : AccessibilityService() {

    lateinit var executor: ActionExecutor
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        executor = ActionExecutor(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no passive processing */ }

    override fun onInterrupt() { AgentState.emergencyStop() }

    // Permission revoked / service switched off by the user -> stop everything.
    override fun onUnbind(intent: Intent?): Boolean {
        AgentState.emergencyStop()
        instance = null
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
    }
}
