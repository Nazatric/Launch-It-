package com.launchit.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.launchit.app.LaunchItApplication
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var totalStorage by remember { mutableStateOf(0L) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var projectCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val app = context.applicationContext as LaunchItApplication
        totalStorage = app.projectRepository.getTotalStorageUsed()
        projectCount = app.projectRepository.getAllProjectsList().size
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Runtime info section
            SettingsSection(title = "Runtime") {
                SettingsItem(
                    icon = Icons.Default.Memory,
                    title = "Runtime Engine",
                    subtitle = "WebView + Local HTTP Server"
                )
                SettingsItem(
                    icon = Icons.Default.CheckCircle,
                    title = "Status",
                    subtitle = "Active"
                )
            }

            // Storage section
            SettingsSection(title = "Storage") {
                SettingsItem(
                    icon = Icons.Default.Storage,
                    title = "Projects",
                    subtitle = "$projectCount imported projects"
                )
                SettingsItem(
                    icon = Icons.Default.DataUsage,
                    title = "Storage Used",
                    subtitle = formatBytes(totalStorage)
                )
                SettingsActionItem(
                    icon = Icons.Default.DeleteSweep,
                    title = "Clear Build Cache",
                    subtitle = "Remove cached build outputs (does not delete project files)",
                    onClick = { showClearCacheDialog = true }
                )
            }

            // Compatibility section
            SettingsSection(title = "Compatibility") {
                SettingsItem(
                    icon = Icons.Default.Web,
                    title = "Static HTML/CSS/JS",
                    subtitle = "Supported ✓"
                )
                SettingsItem(
                    icon = Icons.Default.Code,
                    title = "Canvas & WebGL",
                    subtitle = "Supported ✓"
                )
                SettingsItem(
                    icon = Icons.Default.Build,
                    title = "Frontend Frameworks (React, Vue)",
                    subtitle = "Supported (pre-built projects) ✓"
                )
                SettingsItem(
                    icon = Icons.Default.Dns,
                    title = "Node.js Server",
                    subtitle = "Architecture ready, runtime TBD"
                )
            }

            // About section
            SettingsSection(title = "About") {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "Version",
                    subtitle = "1.0.0"
                )
                SettingsItem(
                    icon = Icons.Default.Description,
                    title = "Description",
                    subtitle = "Local web application and JavaScript game runtime"
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Clear cache confirmation
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear Build Cache") },
            text = { Text("This will remove cached build outputs for all projects. Original project files will not be affected. Are you sure?") },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        val cacheDir = java.io.File(context.cacheDir, "builds")
                        if (cacheDir.exists()) cacheDir.deleteRecursively()
                    }
                    showClearCacheDialog = false
                }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
    }
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
