package com.launchit.app

import android.app.Application
import com.launchit.app.data.AppDatabase
import com.launchit.app.data.ProjectRepository
import com.launchit.app.runtime.ProjectAnalyzer
import com.launchit.app.runtime.ProjectImporter
import com.launchit.app.runtime.RuntimeOrchestrator

/**
 * Application class providing dependency instances.
 */
class LaunchItApplication : Application() {

    lateinit var database: AppDatabase
        private set
    lateinit var projectRepository: ProjectRepository
        private set
    lateinit var runtimeOrchestrator: RuntimeOrchestrator
        private set
    lateinit var projectImporter: ProjectImporter
        private set
    lateinit var projectAnalyzer: ProjectAnalyzer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AppDatabase.getInstance(this)
        projectRepository = ProjectRepository(database.projectDao())
        projectAnalyzer = ProjectAnalyzer()
        projectImporter = ProjectImporter(this, projectRepository, projectAnalyzer)
        runtimeOrchestrator = RuntimeOrchestrator(this, projectRepository)
    }

    override fun onTerminate() {
        super.onTerminate()
        runtimeOrchestrator.cleanup()
    }

    companion object {
        lateinit var instance: LaunchItApplication
            private set
    }
}
