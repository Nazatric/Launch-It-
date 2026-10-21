package com.launchit.app.model

/**
 * The type of imported project, determining how it should be executed.
 */
enum class ProjectType {
    /** Static HTML/CSS/JS project */
    STATIC_WEB,
    /** Frontend framework project (React, Vue, etc.) requiring a build step */
    FRONTEND_APP,
    /** Node.js server application */
    NODE_SERVER,
    /** Node.js server + frontend combined */
    FULL_STACK,
    /** Unknown project type - needs analysis */
    UNKNOWN
}
