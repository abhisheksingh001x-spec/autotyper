package com.autotyper.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.autotyper.app.MainActivity
import com.autotyper.app.R

/**
 * OverlayService
 * ================
 * A Foreground Service that draws a floating control panel over all other apps
 * using Android's WindowManager with TYPE_APPLICATION_OVERLAY.
 *
 * This service:
 *  - Inflates the overlay layout and adds it to the WindowManager
 *  - Handles file selection results forwarded from MainActivity
 *  - Parses the .txt file line-by-line into a message list
 *  - Broadcasts START/STOP commands to AutoTyperAccessibilityService
 *  - Makes the overlay draggable via touch events
 *  - Shows a persistent foreground notification (required on Android 8+)
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID     = "autotyper_overlay_channel"

        // Action for returning file URI from MainActivity to this service
        const val ACTION_FILE_SELECTED      = "com.autotyper.app.ACTION_FILE_SELECTED"
        const val EXTRA_FILE_URI            = "extra_file_uri"

        // Action broadcast back to us when accessibility service stops itself
        const val ACTION_AUTOMATION_STOPPED = "com.autotyper.app.ACTION_AUTOMATION_STOPPED"
    }

    // ── Window Manager ────────────────────────────────────────────────────────

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var layoutParams: WindowManager.LayoutParams

    // ── Overlay UI References ─────────────────────────────────────────────────

    private lateinit var tvFileName: TextView
    private lateinit var etDelay: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // ── State ─────────────────────────────────────────────────────────────────

    private var loadedMessages: ArrayList<String> = arrayListOf()
    private var selectedFileUri: Uri? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerReceivers()
        setupOverlay()
        Log.d(TAG, "OverlayService created and overlay attached")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle file URI forwarded from MainActivity's file picker result
        intent?.getStringExtra(EXTRA_FILE_URI)?.let { uriString ->
            val uri = Uri.parse(uriString)
            handleFileSelected(uri)
        }
        return START_STICKY   // Restart automatically if killed by system
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        unregisterReceivers()
        Log.d(TAG, "OverlayService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Overlay Setup ─────────────────────────────────────────────────────────

    /**
     * Inflates the overlay layout and adds it to the WindowManager.
     * TYPE_APPLICATION_OVERLAY allows it to draw over all other apps.
     */
    private fun setupOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_control_panel, null)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,  // Requires SYSTEM_ALERT_WINDOW
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,         // Don't steal keyboard focus
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100   // Initial X position in pixels
            y = 200   // Initial Y position in pixels
        }

        bindOverlayViews()
        setupDragListener()
        setupButtonListeners()

        windowManager.addView(overlayView, layoutParams)
    }

    /** Gets references to all interactive views in the overlay layout */
    private fun bindOverlayViews() {
        tvFileName = overlayView.findViewById(R.id.tvFileName)
        etDelay    = overlayView.findViewById(R.id.etDelay)
        btnStart   = overlayView.findViewById(R.id.btnStart)
        btnStop    = overlayView.findViewById(R.id.btnStop)

        // Default UI state
        tvFileName.text = "No file loaded"
        etDelay.setText("1000")
        btnStop.isEnabled = false
    }

    /**
     * Attaches a touch listener to the overlay's drag handle (the title bar area)
     * so the user can reposition the overlay by dragging.
     */
    private fun setupDragListener() {
        val dragHandle = overlayView.findViewById<View>(R.id.dragHandle)
        var initialTouchX = 0f
        var initialTouchY = 0f
        var initialX = 0
        var initialY = 0

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    /** Wires up the START and STOP buttons on the overlay */
    private fun setupButtonListeners() {
        // ── Upload / File Select button ───────────────────────────────────────
        overlayView.findViewById<Button>(R.id.btnUpload).setOnClickListener {
            // Send intent to MainActivity to open the file picker
            // MainActivity will forward the result back here via ACTION_FILE_SELECTED
            val pickIntent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_PICK_FILE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(pickIntent)
        }

        // ── START button ──────────────────────────────────────────────────────
        btnStart.setOnClickListener {
            if (!ensureAccessibilityEnabled()) return@setOnClickListener
            if (loadedMessages.isEmpty()) {
                Toast.makeText(this, "Please load a .txt file first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val delayMs = etDelay.text.toString().toLongOrNull() ?: 1000L

            // Broadcast START command to the AccessibilityService
            val startIntent = Intent(AutoTyperAccessibilityService.ACTION_START).apply {
                putStringArrayListExtra(AutoTyperAccessibilityService.EXTRA_MESSAGES, loadedMessages)
                putExtra(AutoTyperAccessibilityService.EXTRA_DELAY_MS, delayMs)
                setPackage(packageName)
            }
            sendBroadcast(startIntent)

            // Update UI state
            btnStart.isEnabled = false
            btnStop.isEnabled  = true
            Toast.makeText(this, "AutoTyper started — ${loadedMessages.size} messages", Toast.LENGTH_SHORT).show()
        }

        // ── STOP button ───────────────────────────────────────────────────────
        btnStop.setOnClickListener {
            stopAutomation()
        }
    }

    /** Sends the STOP broadcast and resets button states */
    private fun stopAutomation() {
        val stopIntent = Intent(AutoTyperAccessibilityService.ACTION_STOP).apply {
            setPackage(packageName)
        }
        sendBroadcast(stopIntent)

        btnStart.isEnabled = true
        btnStop.isEnabled  = false
        Toast.makeText(this, "AutoTyper stopped", Toast.LENGTH_SHORT).show()
    }

    /** Removes the overlay from WindowManager safely */
    private fun removeOverlay() {
        if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
            windowManager.removeView(overlayView)
        }
    }

    // ── File Parsing ──────────────────────────────────────────────────────────

    /**
     * Called when the user picks a .txt file.
     * Reads each non-blank line into [loadedMessages].
     */
    private fun handleFileSelected(uri: Uri) {
        selectedFileUri = uri
        loadedMessages.clear()

        try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                reader.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { loadedMessages.add(it) }
            }

            val fileName = getFileName(uri)
            tvFileName.text = "File: $fileName (${loadedMessages.size} lines)"
            Toast.makeText(
                this,
                "Loaded ${loadedMessages.size} messages from $fileName",
                Toast.LENGTH_LONG
            ).show()
            Log.d(TAG, "File loaded: $fileName — ${loadedMessages.size} messages")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file: ${e.message}", e)
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Extracts the display name from a content URI */
    private fun getFileName(uri: Uri): String {
        var result = uri.lastPathSegment ?: "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) result = cursor.getString(nameIndex)
            }
        }
        return result
    }

    // ── Accessibility Check ───────────────────────────────────────────────────

    /**
     * Checks if our AccessibilityService is currently active.
     * If not, guides the user to enable it in Settings.
     */
    private fun ensureAccessibilityEnabled(): Boolean {
        if (AutoTyperAccessibilityService.instance != null) return true

        Toast.makeText(
            this,
            "Please enable AutoTyper in Settings > Accessibility first",
            Toast.LENGTH_LONG
        ).show()

        // Open Accessibility Settings
        val settingsIntent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(settingsIntent)
        return false
    }

    // ── Broadcast Receivers ───────────────────────────────────────────────────

    private fun registerReceivers() {
        val fileFilter = IntentFilter(ACTION_FILE_SELECTED)
        registerReceiver(fileSelectedReceiver, fileFilter, RECEIVER_NOT_EXPORTED)

        val stopFilter = IntentFilter(ACTION_AUTOMATION_STOPPED)
        registerReceiver(automationStoppedReceiver, stopFilter, RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterReceivers() {
        try { unregisterReceiver(fileSelectedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(automationStoppedReceiver) } catch (_: Exception) {}
    }

    /** Receives file URI after user picks a file from MainActivity */
    private val fileSelectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val uriString = intent?.getStringExtra(EXTRA_FILE_URI) ?: return
            handleFileSelected(Uri.parse(uriString))
        }
    }

    /** Resets button state when the accessibility service finishes all messages */
    private val automationStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            btnStart.isEnabled = true
            btnStop.isEnabled  = false
        }
    }

    // ── Foreground Notification ───────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AutoTyper Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Controls the AutoTyper floating overlay"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoTyper Running")
            .setContentText("Floating overlay is active")
            .setSmallIcon(R.drawable.ic_autotyper_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
