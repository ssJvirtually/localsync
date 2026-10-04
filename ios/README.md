# LocalSync — iOS Application

Native iOS client for **Local Network Photo & Video Backup**, built with **Swift** and **SwiftUI**. It is designed to match the Android client feature-for-feature, connecting seamlessly to the Java desktop server over LAN and Tailscale.

---

## 📱 Features

- **LAN Discovery & Multi-IP Resolution**:
  - Discovers the desktop server automatically via mDNS / Bonjour (`_photobackup._tcp.local.`).
  - Supports fallback to LAN and Tailscale IPs parsed from the QR code or manual input.
  - Automatically verifies reachability via `GET /health` and `POST /pair/verify`.

- **QR Code & Manual Pairing**:
  - Live camera scanner using `AVFoundation` with viewfinder overlay and torch toggle.
  - Manual pairing sheet for entering custom LAN or Tailscale IP (`100.x.y.z`), port, and pairing token.

- **PhotoKit Integration**:
  - Scans user's camera roll (photos and videos) with `Photos` framework.
  - Real-time observation via `PHPhotoLibraryChangeObserver` — taking a new photo immediately schedules it for backup.
  - Oldest-first queue order matching desktop and Android specifications.

- **Fast, Robust Upload Pipeline**:
  - Pre-check `/exists?hash=...&deviceId=...` to skip already-backed-up media without re-uploading.
  - Computes cryptographic SHA-256 hash using Apple's `CryptoKit`.
  - Multipart/form-data upload with live byte-level progress reporting.
  - Dynamic concurrency: 4 concurrent photos / 2 videos on battery, boosted to 6 photos / 3 videos when plugged in and charging.
  - Mobile network protection: Wi-Fi only by default; allows mobile uploads only when connected to PC via Tailscale.

- **Modern SwiftUI UI**:
  - **Photos Tab**:
    - Adaptive grid grouped by date headers.
    - Badges: Green checkmark (`DONE`), progress indicator (`UPLOADING`), play badge (`VIDEO`).
    - Multi-selection mode: batch sharing via iOS Share Sheet and device deletion with photo library confirmation.
    - Fullscreen media viewer: pinch-to-zoom for photos, `AVPlayer` for videos.
  - **Search Tab**:
    - Live filename search with real-time filtering, count, and media viewer integration.
  - **Settings Tab**:
    - Status dashboard with progress bar, backed-up count, and remaining count.
    - Paired PC details (name, active IPs, pairing date, device ID).
    - Sync preferences: Pause/Resume toggle, Sync on Mobile via Tailscale toggle, "Backup Now" trigger.
    - Danger zone: Unpair from PC with confirmation dialog.

- **High-Performance Local Persistence**:
  - Uses native SQLite with Write-Ahead Logging (`PRAGMA journal_mode=WAL;`), ensuring zero contention during concurrent uploads.

---

## 🛠 Project Structure

```
ios/
├── LocalSync.xcodeproj          # Ready-to-open Xcode project
├── project.yml                  # XcodeGen project specification
├── README.md                    # Documentation
└── LocalSync/
    ├── Info.plist               # App permissions (Camera, Photos, Local Network, Bonjour)
    ├── LocalSyncApp.swift       # App entry point
    ├── Assets.xcassets/         # App icon & asset catalog
    ├── Models/
    │   ├── MediaItem.swift      # MediaItem model
    │   ├── PairedServer.swift   # PairedServer model
    │   └── QrPayload.swift      # Codable QR code payload
    ├── Services/
    │   ├── DatabaseManager.swift # Thread-safe SQLite store with WAL mode
    │   ├── PhotoScanner.swift   # PhotoKit scanner & library change observer
    │   ├── DiscoveryManager.swift # Bonjour mDNS & multi-IP fallback resolver
    │   ├── UploadPipeline.swift # SHA-256 & multipart HTTP upload pipeline
    │   ├── SyncEngine.swift     # Queue coordinator with adaptive concurrency
    │   └── NetworkMonitor.swift # NWPathMonitor for Wi-Fi & Tailscale detection
    ├── ViewModels/
    │   └── MainViewModel.swift  # Observable state container
    └── Views/
        ├── MainTabView.swift    # 3-tab layout + contextual selection top bar
        ├── Photos/
        │   ├── PhotosView.swift
        │   ├── PhotoTileView.swift
        │   └── MediaViewerModal.swift
        ├── Search/
        │   └── SearchView.swift
        ├── Settings/
        │   └── SettingsView.swift
        └── Pairing/
            ├── CameraScannerView.swift
            └── ManualPairSheet.swift
```

---

## 🚀 How to Run in Xcode

1. Double click or run:
   ```bash
   open ios/LocalSync.xcodeproj
   ```
2. Select your target device (iOS Simulator or a connected iPhone).
3. Press **Cmd + R** to build and run!

> Note: To regenerate the Xcode project at any time from `project.yml`, run:
> ```bash
> brew install xcodegen # (already installed)
> cd ios && xcodegen generate
> ```
