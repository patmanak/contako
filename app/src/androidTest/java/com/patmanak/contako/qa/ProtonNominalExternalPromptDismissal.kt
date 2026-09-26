package com.patmanak.contako.qa

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/** Safely dismisses the external password-save overlay without activating any prompt action. */
internal object ProtonNominalExternalPromptDismissal {
    fun dismissPasswordSavePrompt(instrumentation: Instrumentation): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        if (root.packageName?.toString() !in PASSWORD_PROMPT_PACKAGES) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var passwordContext = false
        var dismissAction = false
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty()
            if (PASSWORD_CONTEXT_TOKENS.any { text.contains(it, ignoreCase = true) }) {
                passwordContext = true
            }
            if (text in PASSWORD_PROMPT_ACTION_LABELS) dismissAction = true
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return passwordContext && dismissAction && instrumentation.uiAutomation.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_BACK,
        )
    }

    private val PASSWORD_PROMPT_PACKAGES = setOf("foundation.e.passwords", "android")
    private val PASSWORD_CONTEXT_TOKENS = setOf("mot de passe", "password")
    private val PASSWORD_PROMPT_ACTION_LABELS = setOf("Supprimer", "Delete")
}
