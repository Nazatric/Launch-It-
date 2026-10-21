# Launch It — Architecture & Technical Design

## Overview

**launch it** is a native Android application that serves as a local web application and JavaScript game runtime. Users import web applications, games, and JavaScript projects, then launch them from a unified project library with a full-screen immersive experience.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Android Application                    │
├─────────────────────────────────────────────────────────┤
│  UI Layer (Kotlin + Jetpack Compose + Material 3)       │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐  │
│  │ Library  │ │  Import  │ │  Player  │ │ Settings  │  │
│  └──────────┘ └──────────┘ └──────────┘ └───────────┘  │
├─────────────────────────────────────────────────────────┤
│  Runtime Layer                                          │
│  ┌──────────────────┐  ┌────────────────────────────┐  │
│  │ Runtime           │  │  Local HTTP Server         │  │
│  │ Orchestrator      │  │  (NanoHTTPD)               │  │
│  └──────────────────┘  └────────────────────────────┘  │
│  ┌──────────────────┐  ┌────────────────────────────┐  │
│  │ Project Analyzer │  │  Project Importer          │  │
│  └──────────────────┘  └────────────────────────────┘  │
├─────────────────────────────────────────────────────────┤
│  Data Layer                                             │
│  ┌──────────────┐ ┌───────────────┐ ┌──────────────┐  │
│  │ Room Database│ │  Repository   │ │  DataStore   │  │
│  └──────────────┘ └───────────────┘ └──────────────┘  │
├─────────────────────────────────────────────────────────┤
│  Android Platform                                       │
│  ┌──────────┐ ┌──────────────┐ ┌───────────────────┐  │
│  │ WebView  │ │  Shortcuts   │ │  Foreground Svc   │  │
│  └──────────┘ └──────────────┘ └───────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

## Module Structure

| Module | Purpose |
|--------|---------|
| `model/` | Data classes: Project, ProjectType, RuntimeState, DependencyState |
| `data/` | Room database, DAO, Repository for persistent project metadata |
| `runtime/` | HTTP server, project analysis, import, orchestration |
| `ui/library/` | Project library with search, cards, import FAB |
| `ui/import/` | Import workflow and file handling |
| `ui/player/` | Fullscreen WebView-based project execution |
| `ui/settings/` | Runtime status, storage management, diagnostics |
| `ui/theme/` | Material 3 theme with dynamic color support |
| `ui/navigation/` | Navigation graph and route definitions |
| `shortcut/` | Home-screen shortcut creation and launch handling |

## Project Types

### Static Web Projects (Supported ✓)
- HTML/CSS/JavaScript files
- Multiple linked pages
- ES Modules
- Canvas 2D and WebGL
- Three.js and other 3D engines
- Web Audio API
- IndexedDB / LocalStorage
- Images, fonts, audio, video

### Frontend Framework Projects (Supported ✓)
- Pre-built React/Vue/Svelte applications (with `dist/` output)
- Vite/webpack build outputs
- TypeScript and JSX (after build)

### Node.js Server Applications (Architecture Ready)
- The runtime orchestrator detects server projects via package.json
- Architecture supports pluggable execution adapters
- Node.js runtime integration is a planned extension

### Database-Dependent Projects (Documented Limitation)
- PostgreSQL cannot be reliably hosted on Android
- Projects requiring a database must connect to an external instance
- Clean service-provider interface allows future extensions

## Key Design Decisions

### Local HTTP Server
Projects are served via NanoHTTPD on `127.0.0.1` rather than `file://` because:
- ES Modules require an HTTP origin
- `fetch()` API needs proper origin
- Relative paths work correctly
- Service Workers can register properly
- CORS works as expected

### WebView for Execution
Android WebView provides:
- Hardware-accelerated rendering
- WebGL support
- Modern JavaScript (ES2023+)
- Canvas 2D/3D
- Web Audio
- Touch events
- Device API access

### Security
- ZIP extraction validates paths to prevent traversal attacks
- File serving validates canonical paths
- Network security config restricts cleartext to localhost
- TLS certificate validation is never disabled
- Project files are isolated in dedicated directories
- No sensitive launcher data is exposed to project code

## Runtime Lifecycle

```
IMPORTED → ANALYZING → PREPARING → BUILDING → STARTING → RUNNING
                                                         ↓
                                              STOPPING → STOPPED

Failure states: FAILED, CRASHED
```

## Port Management
- Projects are served on ports 8900–9900
- Port allocation checks availability
- Each project gets a unique port
- Ports are tracked in the database

## Home-Screen Shortcuts
- Uses `ShortcutManagerCompat` for compatibility
- Shortcuts identify projects by stable UUID (not file path)
- ShortcutActivity receives the project ID and launches PlayerActivity
- Missing projects show a recovery message
- No per-project APK generation

## Storage Layout

```
context.filesDir/
├── projects/           # Original imported project files
│   ├── project-name/
│   └── ...
context.cacheDir/
├── builds/             # Generated build outputs
└── packages/           # Downloaded dependency cache
context.filesDir/
├── launchit_database   # Room database (project metadata)
```

## Limitations & Known Constraints

1. **Node.js Runtime**: Not yet bundled. Architecture supports it but execution requires a compatible Android Node.js runtime.
2. **Native Add-ons**: Native npm packages requiring compilation are not supported without a build toolchain.
3. **PostgreSQL**: Cannot run a full PostgreSQL server on Android. External database connections only.
4. **WebRTC**: Limited support depending on device WebView version.
5. **File System API**: Projects cannot access arbitrary device files.
6. **Maximum Project Size**: 500MB per archive import.
