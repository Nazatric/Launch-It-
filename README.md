# launch it

A native Android application that acts as a local web application and JavaScript game runtime.

## Overview

**launch it** lets you import web applications, interactive experiences, and web-based games into one Android application, launch them from a project library, and create home-screen shortcuts for direct access.

Each imported project runs in a shared runtime with its own local HTTP server and fullscreen WebView, providing an app-like experience without generating separate APKs.

## Features

### Supported Project Types

| Type | Status | Description |
|------|--------|-------------|
| Static HTML/CSS/JS | ✅ Supported | Multi-page sites, ES modules, JSON, SVG, media |
| Canvas & WebGL | ✅ Supported | 2D/3D graphics, Three.js, game engines |
| Pre-built React/Vue | ✅ Supported | Vite/webpack build outputs with `dist/` directory |
| Frontend frameworks | ✅ Supported | JSX, TypeScript (via build output) |
| Node.js servers | 🔧 Architecture ready | Express, Fastify, etc. (runtime TBD) |
| PostgreSQL/database | ⚠️ Documented limitation | External database connections required |

### Key Capabilities

- **Project Import**: Import ZIP archives or single HTML files via native file picker
- **Local HTTP Server**: Each project is served on a unique port for proper module loading, fetch, and browser API support
- **Fullscreen Player**: Immersive WebView execution with hardware acceleration, touch input, and keyboard support
- **Home-Screen Shortcuts**: Create launcher shortcuts that open projects directly in the runtime
- **Automatic Analysis**: Detects project type, entry point, dependencies, and build requirements
- **Secure Execution**: Path traversal prevention, isolated project directories, TLS verification maintained
- **Material 3 UI**: Clean, modern interface with dynamic colors and dark theme support

## Architecture

```
app/
├── model/          # Data classes (Project, ProjectType, RuntimeState)
├── data/           # Room database, DAO, Repository
├── runtime/        # HTTP server, project analysis, import, orchestration
├── ui/
│   ├── library/    # Project library screen
│   ├── player/     # Fullscreen WebView execution
│   ├── settings/   # Runtime status and storage management
│   ├── theme/      # Material 3 theme
│   └── navigation/ # Navigation routes
└── shortcut/       # Home-screen shortcut creation and handling
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed technical documentation.

## Building

### Prerequisites

- JDK 17+
- Android SDK (API 34)
- Gradle 8.5+

### Build Debug APK

```bash
gradle assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`

### Run Tests

```bash
gradle testDebugUnitTest
```

### Run Lint

```bash
gradle lint
```

## CI/CD

GitHub Actions automatically builds and tests on every push and pull request. The debug APK is uploaded as a build artifact.

## Technology Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Database**: Room (SQLite)
- **HTTP Server**: NanoHTTPD (embedded)
- **Execution**: Android WebView
- **Build**: Gradle 8.5 + AGP 8.2.2
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)

## Security

- ZIP extraction validates paths to prevent directory traversal
- File serving verifies canonical paths stay within project directory
- Network security config restricts cleartext to localhost only
- TLS certificate validation is never disabled
- Project files are isolated in dedicated directories
- Maximum archive size: 500MB, maximum single file: 100MB

## Limitations

- **Node.js Runtime**: The architecture supports Node.js project detection and orchestration, but an Android-compatible Node.js runtime is not yet bundled
- **Native Dependencies**: npm packages requiring native compilation are not supported
- **PostgreSQL**: Cannot run PostgreSQL server on Android; projects must connect to external databases
- **File System Access**: Projects cannot access arbitrary device files
- **WebRTC**: Support depends on device WebView version

## License

This project is provided as-is for development and testing purposes.
