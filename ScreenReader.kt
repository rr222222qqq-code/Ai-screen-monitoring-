package com.example.aiscreenagent

import android.view.accessibility.AccessibilityNodeInfo

object ScreenReader {
    /** Visible labels on screen. Password fields are never read. */
    fun collectTexts(root: AccessibilityNodeInfo, limit: Int = 150): List<String> {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || out.size >= limit || depth > 30) return
            if (n.isPassword) {
                out.add("[password field - hidden]")
            } else {
                val t = n.text?.toString()?.trim().orEmpty()
                val d = n.contentDescription?.toString()?.trim().orEmpty()
                val label = if (t.isNotEmpty()) t else d
                if (label.isNotEmpty()) out.add(label.take(120))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    fun findEditable(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30) return
            if (n.isEditable && n.isVisibleToUser) out.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }
}
