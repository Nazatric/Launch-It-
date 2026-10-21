package com.launchit.app.model

/**
 * Lifecycle states for a project's runtime.
 */
enum class RuntimeState {
    /** Project has been imported but not yet analyzed */
    IMPORTED,
    /** Project is being analyzed to determine execution requirements */
    ANALYZING,
    /** Dependencies are being prepared */
    PREPARING,
    /** Project is being built (if applicable) */
    BUILDING,
    /** Runtime components are starting */
    STARTING,
    /** Project is ready to launch */
    READY,
    /** Project is currently running */
    RUNNING,
    /** Project is being stopped */
    STOPPING,
    /** Project has been stopped */
    STOPPED,
    /** Project failed to start or crashed */
    FAILED,
    /** Project encountered an unrecoverable error */
    CRASHED
}

/**
 * Dependency installation state.
 */
enum class DependencyState {
    /** Not yet checked */
    UNKNOWN,
    /** No dependencies needed */
    NONE,
    /** Dependencies are being installed */
    INSTALLING,
    /** Dependencies are installed and ready */
    INSTALLED,
    /** Dependency installation failed */
    FAILED,
    /** Dependencies need to be reinstalled */
    STALE
}
