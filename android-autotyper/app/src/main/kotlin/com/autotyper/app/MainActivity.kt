package com.autotyper.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.autotyper.app.service.OverlayService

/**
 * MainActivity
 * ==============
 * Entry point of the app. This activity has one job:
 *  1. Request all required permissions (SYSTEM_ALERT_WINDOW, storage, accessibility).
 *  2. Launch the OverlayService (which draws the floating control panel).
 *  3. Handle the file picker intent and forward the result to OverlayService.
 *
 * Once OverlayService is running, the user can navigate away from this activity
 * and the overlay will remain visible over any other app.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Intent action sent to MainActivity to trigger the system file picker */
        const val ACTION_PICK_FILE = "com.autotyper.app.ACTION_PICK_FILE"
    }

    // ── Permission & File Launcher Contracts ──────────────────────────────────

    /** Launches the system file picker filtered to plain text files */
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            forwardFileToOverlayService(uri)
        } else {
            Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show()
        }
    }

    /** Requests storage permission (Android 6–12) */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Storage permission denied — cannot read .txt files",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupUI()
        checkAndRequestPermissions()

        // Handle file pick action if launched from overlay
        if (intent?.action == ACTION_PICK_FILE) {
            launchFilePicker()
        }
    }

    /**
     * Called when OverlayService launches MainActivity with ACTION_PICK_FILE.
     * We need to handle new intents in case activity is already in the back stack.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_PICK_FILE) {
            launchFilePicker()
        }
    }

    // ── UI Setup ──────────────────────────────────────────────────────────────

    private fun setupUI() {
        val tvStatus = findViewById<TextView>(R.id.tvPermissionStatus)
        val btnOverlay = findViewById<Button>(R.id.btnRequestOverlay)
        val btnAccessibility = findViewById<Button>(R.id.btnOpenAccessibility)
        val btnLaunch = findViewById<Button>(R.id.btnLaunchOverlay)

        // ── Request SYSTEM_ALERT_WINDOW (Overlay Permission) ──────────────────
        btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission already granted ✓", Toast.LENGTH_SHORT).show()
            }
        }

        // ── Open Accessibility Settings ───────────────────────────────────────
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "Enable 'AutoTyper' under Installed Services",
                Toast.LENGTH_LONG
            ).show()
        }

        // ── Launch the Floating Overlay ───────────────────────────────────────
        btnLaunch.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(
                    this,
                    "Grant overlay permission first",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val serviceIntent = Intent(this, OverlayService::class.java)
            startForegroundService(serviceIntent)

            Toast.makeText(
                this,
                "Overlay launched — you can now switch to any app",
                Toast.LENGTH_LONG
            ).show()

            // Minimize the app so the user sees the overlay over other apps
            moveTaskToBack(true)
        }
    }

    // ── Permission Handling ───────────────────────────────────────────────────

    /**
     * Checks all required permissions on startup and shows status to the user.
     * Permissions required:
     *   - SYSTEM_ALERT_WINDOW (special permission, must be granted via Settings)
     *   - READ_EXTERNAL_STORAGE (Android 6–12) or READ_MEDIA_IMAGES (Android 13+)
     *   - Accessibility Service (must be enabled manually in Settings)
     */
    private fun checkAndRequestPermissions() {
        val tvStatus = findViewById<TextView>(R.id.tvPermissionStatus)
        val statusLines = StringBuilder()

        // Check overlay permission
        val hasOverlay = Settings.canDrawOverlays(this)
        statusLines.appendLine("• Draw Overlay: ${if (hasOverlay) "✓ Granted" else "✗ Required"}")

        // Request storage permission if needed (API < 33)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val perm = android.Manifest.permission.READ_EXTERNAL_STORAGE
            if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(perm)
            }
            statusLines.appendLine("• Storage: Requested")
        } else {
            statusLines.appendLine("• Storage: Use file picker (no permission needed)")
        }

        tvStatus.text = statusLines.toString()
    }

    override fun onResume() {
        super.onResume()
        // Refresh permission status display when user returns from Settings
        checkAndRequestPermissions()
    }

    // ── File Picker ───────────────────────────────────────────────────────────

    /** Opens the system file picker filtered to text files */
    private fun launchFilePicker() {
        filePickerLauncher.launch("text/plain")
    }

    /**
     * Forwards the selected file URI to OverlayService via broadcast.
     * We use a broadcast so OverlayService can process the file
     * even if MainActivity is about to close.
     */
    private fun forwardFileToOverlayService(uri: Uri) {
        // Grant persistent read permission so OverlayService can read the file
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val broadcast = Intent(OverlayService.ACTION_FILE_SELECTED).apply {
            putExtra(OverlayService.EXTRA_FILE_URI, uri.toString())
            setPackage(packageName)
        }
        sendBroadcast(broadcast)

        Toast.makeText(this, "File sent to AutoTyper overlay", Toast.LENGTH_SHORT).show()
        finish()   // Return to whatever app the user was in
    }
}
