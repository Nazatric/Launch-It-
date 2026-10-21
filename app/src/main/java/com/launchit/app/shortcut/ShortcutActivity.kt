package com.launchit.app.shortcut

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.launchit.app.LaunchItApplication
import com.launchit.app.ui.player.PlayerActivity
import kotlinx.coroutines.*

/**
 * Activity that handles launching projects from home-screen shortcuts.
 * Receives the project ID from the shortcut intent and launches the player.
 */
class ShortcutActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        const val ACTION_SHORTCUT_LAUNCH = "com.launchit.app.ACTION_SHORTCUT_LAUNCH"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)

        if (projectId == null) {
            Toast.makeText(this, "Project not found. It may have been deleted.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Verify project exists
        val app = application as LaunchItApplication
        scope.launch {
            val project = app.projectRepository.getProjectById(projectId)
            if (project == null) {
                runOnUiThread {
                    Toast.makeText(this@ShortcutActivity, "Project not found. It may have been deleted.", Toast.LENGTH_LONG).show()
                    finish()
                }
                return@launch
            }

            // Launch the player activity with the project
            val playerIntent = PlayerActivity.createIntent(this@ShortcutActivity, projectId)
            playerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(playerIntent)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
