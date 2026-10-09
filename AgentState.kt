package com.example.aiscreenagent

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Process-wide agent state. Single place that knows if automation may run. */
object AgentState {
    @Volatile var active = false          // user pressed Start Agent
    @Volatile var stopRequested = false   // emergency stop latch, cleared only by Start Agent
    @Volatile var maxActions = 10

    private val count = AtomicInteger(0)
    val history = CopyOnWriteArrayList<String>()

    private var pool: ExecutorService = Executors.newSingleThreadExecutor()

    @Synchronized fun submit(r: Runnable) { pool.execute(r) }

    /** Interrupts the running action and drops everything queued. */
    @Synchronized fun emergencyStop() {
        active = false
        stopRequested = true
        pool.shutdownNow()
        pool = Executors.newSingleThreadExecutor()
    }

    fun start() { stopRequested = false; active = true }

    fun resetTask() { count.set(0) }
    fun tryCountAction(): Boolean = count.incrementAndGet() <= maxActions

    fun log(line: String) {
        history.add(line)
        while (history.size > 200) history.removeAt(0)
    }
}
