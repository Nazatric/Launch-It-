package com.launchit.app.ui.navigation

sealed class Screen(val route: String) {
    object Library : Screen("library")
    object Import : Screen("import")
    object Settings : Screen("settings")
    object ProjectDetails : Screen("project/{projectId}") {
        fun createRoute(projectId: String) = "project/$projectId"
    }
}
