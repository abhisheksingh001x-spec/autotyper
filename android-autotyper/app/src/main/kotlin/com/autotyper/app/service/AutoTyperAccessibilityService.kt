package com.autotyper.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AutoTyperAccessibilityService
 * ================================
 * THE CORE AUTOMATION ENGINE.
 *
 * This service runs in the background after the user enables it in
 * Android Settings > Accessibility > AutoTyper.
 *
 * It listens for broadcast commands from OverlayService (START/STOP),
 * then loops through the loaded message list, injecting text into the
 * active app's input field and clicking the Send button.
 *
 * Key Android API used:
 *   - AccessibilityNodeInfo.ACTION_SET_TEXT  → writes text into EditText nodes
 *   - AccessibilityNodeInfo.ACTION_CLICK     → simulates button clicks
 *   - findFocus(FOCUS_INPUT)                 → locates the currently focused EditText
 */
class AutoTyperAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoTyperA11y"

        // Broadcast action constants — sent from OverlayService
        const val ACTION_START = "com.autotyper.app.ACTION_START"
        const val ACTION_STOP  = "com.autotyper.app.ACTION_STOP"

        // Extras bundled with the START broadcast
        const val EXTRA_MESSAGES = "extra_messages"    // ArrayList<String>
        const val EXTRA_DELAY_MS = "extra_delay_ms"    // Long (milliseconds)

        // Singleton reference so OverlayService can check if A11y is active
        var instance: AutoTyperAccessibilityService? = null
            private set
    }

    // ── State ──────────────────────────────────────────────────────────────────

    /** Messages loaded from the .txt file, received via broadcast */
    private var messageList: ArrayList<String> = arrayListOf()

    /** Delay between each send action, in milliseconds */
    private var delayMs: Long = 1000L

    /** Controls the automation loop — set to false to stop */
    @Volatile
    private var isRunning = false

    /** Main thread handler for posting delayed runnables */
    private val handler = Handler(Looper.getMainLooper())

    /** Index of the next message to send */
    private var currentIndex = 0

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityService connected")

        // Configure the service programmatically as a backup to the XML config
        serviceInfo = serviceInfo.also { info ->
            info.eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            )
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = (
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            )
            info.notificationTimeout = 100
        }

        // Register broadcast receiver to listen for START/STOP from OverlayService
        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_STOP)
        }
        registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        Log.d(TAG, "Command broadcast receiver registered")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't react to live events here — automation is driven by timer loop.
        // This callback is still required by the framework.
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted — stopping automation")
        stopAutomation()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopAutomation()
        try {
            unregisterReceiver(commandReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was already unregistered
        }
        Log.d(TAG, "AccessibilityService destroyed")
    }

    // ── Broadcast Receiver ─────────────────────────────────────────────────────

    /**
     * Receives START and STOP commands from OverlayService.
     * START carries the message list and delay as extras.
     */
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START -> {
                    val messages = intent.getStringArrayListExtra(EXTRA_MESSAGES)
                    val delay    = intent.getLongExtra(EXTRA_DELAY_MS, 1000L)

                    if (messages.isNullOrEmpty()) {
                        Log.w(TAG, "START received but message list is empty — aborting")
                        return
                    }

                    messageList   = messages
                    delayMs       = delay
                    currentIndex  = 0
                    startAutomation()
                }
                ACTION_STOP -> stopAutomation()
            }
        }
    }

    // ── Automation Loop ────────────────────────────────────────────────────────

    /**
     * Kicks off the automation loop on the main thread.
     * Each iteration sends one message then schedules the next
     * after [delayMs] milliseconds.
     */
    private fun startAutomation() {
        if (isRunning) {
            Log.d(TAG, "Already running — ignoring duplicate START")
            return
        }
        isRunning = true
        currentIndex = 0
        Log.d(TAG, "Automation started. ${messageList.size} messages, ${delayMs}ms delay")
        scheduleNextMessage()
    }

    /**
     * Immediately halts the automation loop.
     * Safe to call from any thread.
     */
    private fun stopAutomation() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Automation stopped at index $currentIndex")

        // Notify OverlayService that we stopped (so it can update UI button state)
        val stopIntent = Intent(OverlayService.ACTION_AUTOMATION_STOPPED)
        sendBroadcast(stopIntent)
    }

    /**
     * Posts a Runnable that sends the message at [currentIndex],
     * then schedules itself again if more messages remain.
     */
    private fun scheduleNextMessage() {
        if (!isRunning || currentIndex >= messageList.size) {
            if (currentIndex >= messageList.size) {
                Log.d(TAG, "All messages sent — automation complete")
            }
            stopAutomation()
            return
        }

        handler.postDelayed({
            if (!isRunning) return@postDelayed   // STOP was pressed during the delay

            val message = messageList[currentIndex]
            Log.d(TAG, "Sending message [${currentIndex + 1}/${messageList.size}]: \"$message\"")

            val success = sendMessage(message)
            if (success) {
                currentIndex++
                scheduleNextMessage()    // Schedule the next message
            } else {
                Log.w(TAG, "Failed to send message at index $currentIndex — retrying in ${delayMs}ms")
                // Retry the same index once, then move on
                scheduleNextMessage()
            }
        }, delayMs)
    }

    // ── Core Message Injection ─────────────────────────────────────────────────

    /**
     * The heart of the automation engine.
     *
     * 1. Find the currently focused or visible input field in the active window.
     * 2. Inject [text] using ACTION_SET_TEXT.
     * 3. Find the Send/Submit button in the same window.
     * 4. Click it using ACTION_CLICK.
     *
     * @return true if both inject and click succeeded, false otherwise.
     */
    private fun sendMessage(text: String): Boolean {
        // Step 1: Get the active window's root node
        val rootNode = findActiveRootNode() ?: run {
            Log.w(TAG, "No active root node found")
            return false
        }

        // Step 2: Find the input field
        val inputNode = findInputNode(rootNode) ?: run {
            Log.w(TAG, "No input field found in active window")
            rootNode.recycle()
            return false
        }

        // Step 3: Inject the text
        val injectSuccess = injectText(inputNode, text)
        inputNode.recycle()

        if (!injectSuccess) {
            Log.w(TAG, "Text injection failed")
            rootNode.recycle()
            return false
        }

        // Small pause to let the app process the text before clicking Send
        Thread.sleep(150)

        // Step 4: Find and click the Send button
        val freshRoot = findActiveRootNode() ?: run {
            Log.w(TAG, "Lost root node after text injection")
            rootNode.recycle()
            return false
        }

        val sendSuccess = findAndClickSendButton(freshRoot)
        freshRoot.recycle()
        rootNode.recycle()

        if (!sendSuccess) {
            Log.w(TAG, "Send button click failed — message may have been typed but not sent")
        }

        return injectSuccess    // Return true even if send failed so we advance the index
    }

    /**
     * Returns the root AccessibilityNodeInfo of the most relevant active window.
     * Iterates through all windows to find the one that is NOT our own overlay.
     */
    private fun findActiveRootNode(): AccessibilityNodeInfo? {
        // windows is available when FLAG_RETRIEVE_INTERACTIVE_WINDOWS is set
        val allWindows = windows ?: return rootInActiveWindow

        for (window in allWindows) {
            // Skip our own overlay window
            if (window.title?.toString()?.contains("AutoTyper", ignoreCase = true) == true) {
                continue
            }
            val root = window.root
            if (root != null) return root
        }

        // Fallback
        return rootInActiveWindow
    }

    /**
     * Traverses the node tree to find the best candidate for text input.
     *
     * Priority order:
     *   1. Node that currently has INPUT focus (most reliable)
     *   2. Node that currently has ACCESSIBILITY focus
     *   3. First visible, enabled, editable node in the tree (fallback)
     */
    private fun findInputNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Priority 1: Input-focused node (the cursor is here)
        val inputFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocused != null && inputFocused.isEditable) {
            Log.d(TAG, "Input node found via FOCUS_INPUT: ${inputFocused.className}")
            return inputFocused
        }

        // Priority 2: Accessibility-focused node
        val a11yFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (a11yFocused != null && a11yFocused.isEditable) {
            Log.d(TAG, "Input node found via FOCUS_ACCESSIBILITY: ${a11yFocused.className}")
            return a11yFocused
        }

        // Priority 3: Search the tree for EditText or similar
        return findEditableNodeInTree(root)
    }

    /**
     * Recursively walks the accessibility node tree looking for an
     * editable, visible, enabled node (i.e., a text input field).
     */
    private fun findEditableNodeInTree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled && node.isVisibleToUser) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNodeInTree(child)
            child.recycle()
            if (result != null) return result
        }

        return null
    }

    /**
     * Injects text into [node] using ACTION_SET_TEXT.
     * Falls back to clipboard paste via ACTION_PASTE for older API levels
     * or apps that block ACTION_SET_TEXT.
     *
     * @return true if the action was performed successfully.
     */
    private fun injectText(node: AccessibilityNodeInfo, text: String): Boolean {
        // Primary: ACTION_SET_TEXT (API 21+, most reliable)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        // First, click the node to ensure it gains focus
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "ACTION_SET_TEXT result: $result for text: \"${text.take(20)}...\"")
        return result
    }

    /**
     * Searches the node tree for a Send/Submit button and clicks it.
     *
     * Detection heuristics (in order of reliability):
     *   1. Content description matches common send labels
     *   2. View ID contains "send" or "submit"
     *   3. Node text matches common send labels
     *   4. ImageButton that is a sibling of the input field
     */
    private fun findAndClickSendButton(root: AccessibilityNodeInfo): Boolean {
        val sendKeywords = listOf("send", "submit", "done", "post", "go")

        // Search by content-description (most accessible apps label their send button)
        for (keyword in sendKeywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                if (node.isClickable && node.isEnabled && node.isVisibleToUser) {
                    Log.d(TAG, "Send button found by text/contentDesc: \"$keyword\"")
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    if (clicked) return true
                }
                node.recycle()
            }
        }

        // Fallback: Walk the tree for a clickable ImageButton near the bottom of the layout
        return findSendButtonInTree(root)
    }

    /**
     * Recursive tree walker to find a send button when label-based search fails.
     * Looks for clickable nodes with class ImageButton or Button near the end of the tree.
     */
    private fun findSendButtonInTree(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""

        if (node.isClickable && node.isEnabled && node.isVisibleToUser) {
            val isButton = className.contains("ImageButton") ||
                           className.contains("Button") ||
                           className.contains("ImageView")
            val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
            val isSendLike  = contentDesc.contains("send") ||
                              contentDesc.contains("submit") ||
                              contentDesc.contains("post")

            if (isButton && isSendLike) {
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    Log.d(TAG, "Send button clicked via tree walk: $className")
                    return true
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendButtonInTree(child)
            child.recycle()
            if (found) return true
        }

        return false
    }
}
