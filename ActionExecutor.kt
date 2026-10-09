package com.example.aiscreenagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs validated actions. Call from a background thread (it waits for the UI to settle). */
class ActionExecutor(private val svc: AgentAccessibilityService) {

    private val settleMs = 900L
    private val noAccess =
        "Is screen ka content padh nahi sakta - app ya screen protected (secure) ho sakti hai, ya koi window active nahi hai."

    fun execute(a: Action, confirmed: Boolean): ActionResult {
        return try {
            run(a, confirmed)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ActionResult(false, false, "Emergency stop - action beech mein roka gaya.")
        } catch (e: Exception) {
            ActionResult(false, false, "Error: ${e.javaClass.simpleName}")
        }
    }

    private fun run(a: Action, confirmed: Boolean): ActionResult {
        if (!AgentState.active) return ActionResult(false, false, "Agent start nahi hai. Pehle Start Agent dabao.")
        if (AgentState.stopRequested) return ActionResult(false, false, "Emergency stop active hai.")

        val dm = svc.resources.displayMetrics
        val v = ActionValidator.validate(a, dm.widthPixels, dm.heightPixels)
        if (!v.ok) return ActionResult(false, false, "Action reject: ${v.message}")

        if (ActionValidator.needsConfirmation(a) && !confirmed)
            return ActionResult(false, false, "Ye action sensitive hai: '${a.text}'. Confirmation chahiye.", true)

        if (!AgentState.tryCountAction())
            return ActionResult(false, false, "Max action limit (${AgentState.maxActions}) poori ho gayi. Task roka gaya.")

        val before = signature()
        val r: ActionResult = when (a.type) {
            "inspect_screen" -> inspect()
            "press_back" -> {
                val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                afterChange(ok, before, "Back dabaya")
            }
            "open_settings" -> {
                svc.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                afterChange(true, before, "Settings khola")
            }
            "tap_text" -> tapText(a.text!!, before)
            "tap" -> {
                val x = a.x!!.toFloat()
                val y = a.y!!.toFloat()
                afterChange(gesture(x, y, x, y, 50), before, "Tap (${a.x}, ${a.y})")
            }
            "swipe" -> swipe(a.direction!!, before, dm.widthPixels, dm.heightPixels)
            "type_text" -> typeText(a.text!!)
            "wait" -> {
                Thread.sleep(a.ms!!)
                ActionResult(true, true, "${a.ms} ms wait kiya")
            }
            "ask_confirmation" -> ActionResult(false, false, "AI ne confirmation maanga hai.", true)
            "finish" -> ActionResult(true, true, "Task complete.")
            else -> ActionResult(false, false, "Unsupported action")
        }
        if (AgentState.stopRequested) return ActionResult(r.executed, false, r.message + " (stop ke baad verify nahi kiya)")
        return r
    }

    private fun inspect(): ActionResult {
        val root = svc.rootInActiveWindow ?: return ActionResult(false, false, noAccess)
        val texts = ScreenReader.collectTexts(root)
        if (texts.isEmpty()) return ActionResult(true, false, "Screen par koi readable text nahi mila. $noAccess")
        return ActionResult(true, true, "App: ${root.packageName}\nScreen par: " + texts.joinToString(" | "))
    }

    private fun tapText(label: String, before: String): ActionResult {
        val root = svc.rootInActiveWindow ?: return ActionResult(false, false, noAccess)
        val targets = root.findAccessibilityNodeInfosByText(label)
            .mapNotNull { clickableAncestor(it) }
            .distinct()
        return when {
            targets.isEmpty() ->
                ActionResult(false, false, "'$label' screen par clickable nahi mila. Scroll karke ya 'screen par kya dikh raha hai' se check karo.")
            targets.size > 1 ->
                ActionResult(false, false, "'$label' ke ${targets.size} matches mile. Kaunsa chahiye? Thoda aur specific naam batao.")
            else -> {
                val ok = targets[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                afterChange(ok, before, "'$label' par tap kiya")
            }
        }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var i = 0
        while (n != null && i < 8) {
            if (n.isClickable && n.isVisibleToUser) return n
            n = n.parent
            i++
        }
        return null
    }

    private fun swipe(dir: String, before: String, w: Int, h: Int): ActionResult {
        val cx = w / 2f
        val cy = h / 2f
        // Finger moves opposite to the direction the content scrolls.
        val ok = when (dir) {
            "down" -> gesture(cx, h * 0.7f, cx, h * 0.3f, 300)
            "up" -> gesture(cx, h * 0.3f, cx, h * 0.7f, 300)
            "left" -> gesture(w * 0.8f, cy, w * 0.2f, cy, 300)
            else -> gesture(w * 0.2f, cy, w * 0.8f, cy, 300)
        }
        return afterChange(ok, before, "Scroll $dir")
    }

    private fun typeText(text: String): ActionResult {
        val root = svc.rootInActiveWindow ?: return ActionResult(false, false, noAccess)
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val node: AccessibilityNodeInfo
        if (focused != null && focused.isEditable) {
            node = focused
        } else {
            val fields = ScreenReader.findEditable(root)
            if (fields.isEmpty()) return ActionResult(false, false, "Screen par koi text box nahi mila.")
            if (fields.size > 1) return ActionResult(false, false, "${fields.size} text boxes hain. Pehle jis box mein type karna hai usko tap karo, phir command do.")
            node = fields[0]
        }
        if (node.isPassword) return ActionResult(false, false, "Password field mein typing blocked hai.")
        val hint = (node.hintText?.toString() ?: "") + (node.viewIdResourceName ?: "")
        if (hint.lowercase().contains("otp")) return ActionResult(false, false, "OTP field mein typing blocked hai.")

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Thread.sleep(400)
        node.refresh()
        val verified = node.text?.toString()?.contains(text) == true
        return ActionResult(ok, verified, if (verified) "Text type kiya aur verify hua" else "Text type karne ki koshish ki, par verify nahi ho paya")
    }

    private fun afterChange(performed: Boolean, before: String, what: String): ActionResult {
        if (!performed) return ActionResult(false, false, "$what - system ne action accept nahi kiya.")
        Thread.sleep(settleMs)
        val changed = signature() != before
        return ActionResult(
            true, changed,
            if (changed) "$what - screen badli (verified)" else "$what - par screen mein change nahi dikha (verify nahi hua)"
        )
    }

    private fun signature(): String {
        val root = svc.rootInActiveWindow ?: return "none"
        return root.packageName.toString() + "|" + ScreenReader.collectTexts(root).hashCode()
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durMs)).build()
        val latch = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val accepted = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done.set(true); latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, Handler(Looper.getMainLooper()))
        if (!accepted) return false
        latch.await(3, TimeUnit.SECONDS)
        return done.get()
    }
}
