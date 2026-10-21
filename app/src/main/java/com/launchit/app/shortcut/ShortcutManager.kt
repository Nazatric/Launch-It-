package com.launchit.app.shortcut

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.launchit.app.model.Project

/**
 * Manages creation of home-screen shortcuts for projects.
 */
object ShortcutManager {

    fun createShortcut(context: Context, project: Project): Boolean {
        val shortcutIntent = Intent(context, ShortcutActivity::class.java).apply {
            action = ShortcutActivity.ACTION_SHORTCUT_LAUNCH
            putExtra(ShortcutActivity.EXTRA_PROJECT_ID, project.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val shortcutInfo = ShortcutInfoCompat.Builder(context, "project_${project.id}")
            .setShortLabel(project.displayName)
            .setLongLabel(project.displayName)
            .setIntent(shortcutIntent)
            .setIcon(createProjectIcon(project))
            .build()

        return ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
    }

    fun removeShortcut(context: Context, projectId: String) {
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf("project_$projectId"))
    }

    fun isRequestPinShortcutSupported(context: Context): Boolean {
        return ShortcutManagerCompat.isRequestPinShortcutSupported(context)
    }

    private fun createProjectIcon(project: Project): IconCompat {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint().apply {
            color = Color.parseColor("#6750A4")
            isAntiAlias = true
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = size * 0.4f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
        val initial = project.displayName.firstOrNull()?.uppercase() ?: "P"
        val y = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initial, size / 2f, y, textPaint)

        return IconCompat.createWithBitmap(bitmap)
    }
}
