package com.launchit.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.launchit.app.model.Project
import com.launchit.app.ui.library.LibraryScreen
import com.launchit.app.ui.navigation.Screen
import com.launchit.app.ui.player.PlayerActivity
import com.launchit.app.ui.settings.SettingsScreen
import com.launchit.app.ui.theme.LaunchItTheme

/**
 * Main activity - the app's entry point and navigation host.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LaunchItTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = Screen.Library.route
                    ) {
                        composable(Screen.Library.route) {
                            LibraryScreen(
                                onNavigateToSettings = {
                                    navController.navigate(Screen.Settings.route)
                                },
                                onLaunchProject = { project ->
                                    launchProject(project)
                                }
                            )
                        }
                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun launchProject(project: Project) {
        val intent = PlayerActivity.createIntent(this, project.id)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
