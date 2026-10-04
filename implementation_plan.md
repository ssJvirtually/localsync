# Local Network Photo Backup — Implementation Plan

## 1. Overview

Two apps working together over the local network:

- **Android app** — scans DCIM/selected folders, tracks backup status locally, uploads photos/videos to the paired PC.
- **PC app (Java)** — always-running background app that advertises itself on the network, receives uploads, and stores them permanently.

No cloud, no internet dependency — everything happens over LAN via mDNS discovery + local HTTP.

---

## 2. Architecture

```
┌─────────────────────────────┐            ┌──────────────────────────────┐
│         ANDROID APP          │            │           PC APP             │
│                               │            │                              │
│  UI (Compose)                 │            │  JavaFX UI                   │
│  ├─ PhotosScreen              │            │  ├─ Pairing screen (QR code) │
│  ├─ SearchScreen               │            │  └─ Status screen (synced   │
│  ├─ SettingsScreen             │            │      devices, files, sizes) │
│  └─ PairingScreen              │            │                              │
│                               │  LAN HTTP  │  HTTP Server (Javalin, plain │
│  Domain                       │◄──────────►│  HTTP — no TLS for v1)       │
│  ├─ MediaScanner (MediaStore) │            │  ├─ /health                 │
│  ├─ BackupQueueManager        │            │  ├─ /pair/verify             │
│  ├─ UploadWorkerPool          │            │  ├─ /upload                  │
│  ├─ NetworkMonitor            │  mDNS      │  └─ /exists                  │
│  └─ DiscoveryManager (NSD)    │◄──────────►│                              │
│                               │            │  mDNS Advertiser (JmDNS)     │
│  Data                         │            │  service: _photobackup._tcp  │
│  ├─ Room DB                   │            │                              │
│  └─ DataStore (token/prefs)   │            │  Storage                     │
│                               │            │  ├─ /backups/<deviceId>/...  │
│  Service                      │            │  └─ SQLite (WAL mode)        │
│  └─ BackupForegroundService   │            │      via sqlite-jdbc         │
└─────────────────────────────┘            └──────────────────────────────┘
```

---

## 3. Pairing Flow

1. PC app first launch → generates a random `token` (UUID) and a `deviceName` (hostname).
2. PC advertises itself via JmDNS as `_photobackup._tcp.local.` with TXT record `{port}`.
3. PC's JavaFX pairing screen displays a QR code encoding: `{ "service": "_photobackup._tcp.local.", "token": "...", "pcName": "...", "port": ..., "localIp": "..." }`. The `localIp` field is a **fallback**, not the primary mechanism — see note below.
4. Android scans QR (ML Kit) → stores `token`, `pcName`, and the fallback `localIp` in Room (`PairedServer` table).
5. Android resolves the service via `NsdManager` to get the current IP + port (preferred path).
6. Android calls `POST /pair/verify` with the token → PC validates, generates a `deviceId` for this phone, returns it.
7. Android stores `deviceId` alongside the paired server record.
8. **Reconnect strategy (every sync attempt):** try the mDNS-resolved address first; if resolution fails or times out (e.g. multicast blocked on the router), fall back to the cached `localIp` from the QR code. If both fail, pause and wait for the next discovery attempt.

**Why the IP fallback matters:** some routers — mesh systems, guest networks, AP/client isolation — block multicast traffic, which silently breaks mDNS with no clear error. The cached IP gives the app a working fallback on those networks. It's not a long-term substitute for mDNS, though: if the PC's IP changes *and* mDNS is also blocked, the cached IP goes stale and the user needs to re-scan the QR code once to resync. Acceptable, rare edge case for v1.

**Note:** v1 supports **one active paired PC per phone**. Multi-PC support is a clean extension later (schema already allows a `PairedServer` table, just add UI for switching).

---

## 4. Data Models

### 4.1 Android — Room

**`MediaItem`**
| Field | Type | Notes |
|---|---|---|
| mediaId | Long (PK) | From MediaStore |
| filePath | String | |
| fileName | String | |
| dateTaken | Long | Epoch ms, drives queue order |
| fileHash | String? | SHA-256, computed lazily before upload |
| sizeBytes | Long | |
| mediaType | Enum | PHOTO / VIDEO |
| backupStatus | Enum | PENDING / UPLOADING / DONE / FAILED |
| lastAttemptAt | Long? | For retry backoff |
| pairedServerId | Long (FK) | |

**`PairedServer`**
| Field | Type | Notes |
|---|---|---|
| id | Long (PK) | |
| serviceName | String | mDNS service name |
| pcName | String | Display name |
| token | String | Pairing secret |
| deviceId | String | Assigned by PC during /pair/verify |
| fallbackIp | String | From QR code; used only if mDNS resolution fails |
| pairedAt | Long | |

Upload progress (0–100%) is **not persisted** — kept in an in-memory `MutableStateFlow<Map<mediaId, Int>>` inside the service, since it's only relevant while the app/service is alive.

### 4.2 PC — SQLite (WAL mode)

**`devices`**
| Field | Type |
|---|---|
| device_id | TEXT (PK, UUID) |
| device_name | TEXT |
| token | TEXT |
| paired_at | INTEGER |

**`received_files`**
| Field | Type |
|---|---|
| id | INTEGER (PK autoincrement) |
| device_id | TEXT (FK) |
| media_id | INTEGER (Android's ID, for reference/debugging) |
| file_hash | TEXT |
| file_name | TEXT |
| stored_path | TEXT |
| size_bytes | INTEGER |
| date_taken | INTEGER |
| received_at | INTEGER |

`file_hash` + `device_id` should have a unique index — this is what lets `/exists` catch already-backed-up files even if Android's local Room DB ever gets wiped (reinstall, cache clear).

---

## 5. API Contract (PC HTTP server)

**Transport:** plain HTTP (no TLS) for v1 — simpler to build and debug, and the token-based auth already prevents random devices on the LAN from uploading garbage even without encryption. Android will need `android:usesCleartextTraffic="true"` (or a network security config scoped to local addresses) since Android blocks cleartext HTTP by default from API 28+.

All requests except pairing require header: `Authorization: Bearer <token>`

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Lightweight ping — used by discovery/backstop checks. Returns `{status:"ok"}` |
| POST | `/pair/verify` | Body: `{token}`. PC validates, returns `{deviceId}` |
| POST | `/upload` | Multipart: file + `{mediaId, hash, dateTaken, fileName, deviceId, mediaType}`. Returns `{status:"success", hash, receivedAt}` |
| GET | `/exists?hash=&deviceId=` | Returns `{exists: true/false}` — recovery aid if local DB is lost |
| GET | `/status?deviceId=` | Returns `{filesReceived, totalBytes}` — optional, for cross-checking Settings screen counts |

---

## 6. Sync Engine Design

### Queue order
Items are queued **ascending by `dateTaken`** — oldest first. Rationale (per earlier discussion): older photos are at higher risk of being deleted/lost before backup completes, and users tend to keep recent photos on-device longer anyway.

**Accepted tradeoff:** if there's a large backlog (e.g. 10,000 photos), brand-new photos taken during the catch-up period will queue behind the entire backlog rather than jumping ahead. This is intentional given the oldest-first policy — flagging it so it's a known behavior, not a bug.

### Concurrency
Fixed worker pool using coroutines + semaphores:
- `Semaphore(4)` for photos
- `Semaphore(2)` for videos

Both pools pull from the same Room-backed queue (`WHERE backupStatus = PENDING ORDER BY dateTaken ASC`).

### Upload lifecycle per item
1. Mark `UPLOADING`.
2. Compute hash if not already cached.
3. Multipart upload with progress callback → updates in-memory progress `StateFlow`.
4. On success: mark `DONE`, store hash.
5. On failure (timeout, PC unreachable): mark back to `PENDING`, increment retry counter with backoff.
6. On network transport leaving Wi-Fi mid-upload: **abort immediately**, discard any partial file on PC side (PC deletes incomplete temp file), mark item `PENDING` again — no resume, restart from zero next time (per earlier decision).

### Network monitoring
`ConnectivityManager.NetworkCallback`:
- `onAvailable` (Wi-Fi) → trigger `DiscoveryManager.discover()` → resolve PC via NSD → `/health` check → resume worker pool if healthy.
- Transport changes away from Wi-Fi → cancel all active uploads, pause pool, update notification to "Paused — not on Wi-Fi."

### Discovery
- **Primary trigger:** Wi-Fi connect event (immediate).
- **Backstop:** `WorkManager` periodic task (~15 min, Android's practical minimum) in case the PC app restarted or discovery silently failed earlier.

### New photo detection
`ContentObserver` on MediaStore URI → on any insert, add new `MediaItem` row as `PENDING` → naturally joins the queue (at the "new" end, since it's the most recent `dateTaken`).

---

## 7. Screen-by-Screen UI

### Photos screen (home)
- Grid of thumbnails (Coil), grouped by month like Google Photos.
- Top bar: app title, Search icon, Settings icon (top right).
- Per-thumbnail overlay:
  - **Green tick** (bottom-right) — `DONE`
  - **Small spinner with % arc** (bottom-right) — `UPLOADING` (mainly visible on videos; photos usually finish too fast to notice)
  - **Nothing** — `PENDING` / `FAILED`

### Search screen
- Filter by date range and by folder/bucket.
- Optional simple filename text search.
- Keep scope minimal for v1 — no ML-based search.

### Settings screen
- Status card: "X of Y backed up", storage used, last sync time.
- Paired PC info: name, re-pair button.
- Folder selection: DCIM/Camera checked by default, "add folder" to include others.
- Manual "Backup now" trigger (forces a discovery + resume attempt).
- Pause/resume backup toggle.

### Pairing screen (first launch only)
- Camera view for QR scan (ML Kit).
- "Connecting..." state while resolving + verifying.
- Success → immediately kicks off full DCIM scan and starts the queue.

---

## 8. PC App Details

- **Javalin** server (plain HTTP, no TLS for v1), routes as above.
- **JmDNS** advertises `_photobackup._tcp.local.` on app start; re-advertises if port changes.
- **SQLite (WAL mode)** — enable via `PRAGMA journal_mode=WAL;` on connection init, required because 4+ concurrent upload requests will write to `received_files` at once.
- **JavaFX UI** (replaces tray-only design) with two views:
  - **Pairing screen** — displays the QR code (service name, token, pcName, port, local IP fallback), regenerate button if the token needs resetting.
  - **Status screen** — list of paired devices, files received per device, total storage used, last sync time per device. Simple table/list view is enough for v1.
  - App still runs in the background/minimizes to tray for convenience, but the QR + status views live in an actual window rather than being tray-menu-only.
- **File storage layout:** backups organized in **per-device subfolders** to avoid filename collisions and keep multi-phone setups clean: `/backups/<deviceId>/<fileName>`. `deviceId` is the UUID assigned during pairing, so folder names stay stable even if the user renames their phone later.
- **jpackage** produces native installers:
  - Windows → `.exe`
  - macOS → `.dmg`
  - Linux → `.deb` / AppImage
  - Bundles a minimal JRE, so end users never install Java separately.

---

## 9. Known Tradeoffs / Accepted Limitations (v1)

- One paired PC per phone at a time (schema supports more later, UI doesn't yet).
- No resume on interrupted uploads — restarts from zero (simplicity over efficiency).
- Fixed concurrency (4 photos / 2 videos), not user-tunable.
- Oldest-first queue means new photos wait behind a large existing backlog.
- Pure backup semantics — PC never deletes anything, even if the phone does.
- No cloud fallback — if PC is off or unreachable, backup simply pauses and resumes automatically once it's back.
- Plain HTTP, no TLS — acceptable for a trusted home LAN given token-based auth; revisit if this ever needs to work on untrusted/public networks.
- IP-in-QR is a fallback only — if the PC's IP changes *and* mDNS is also blocked on that network, the user needs to re-scan the QR code once to resync.

---

## 10. Build Order

1. **PC skeleton** — Javalin server + JmDNS advertise + JavaFX pairing/status window (QR generation) + SQLite (WAL). Verify discoverability with an external mDNS browser tool before writing any Android code.
2. **Android skeleton** — Compose scaffold (3 screens), Room entities, full MediaStore scan into Room (no upload yet). Verify grid renders correctly, ascending date order confirmed via a debug log/list.
3. **Pairing flow end-to-end** — QR scan → NSD resolve → `/pair/verify`. Confirm PC tray reflects a newly paired device.
4. **Single-item upload pipeline** — `/upload` endpoint + one-at-a-time OkHttp multipart upload from Android. Get correctness right (hash verification, green tick appears) before adding concurrency.
5. **Concurrency** — add the semaphore-based worker pool, verify multiple progress spinners run simultaneously without SQLite write contention (WAL confirmed working under load).
6. **Network awareness** — implement Wi-Fi/mobile-data switch handling; test by toggling networks mid-backup, confirm clean abort + restart-from-zero behavior.
7. **Foreground service + notification + WorkManager backstop** — test app backgrounded/killed, battery-optimization exemption prompt.
8. **ContentObserver for new photos** — test taking a new photo mid-backlog, confirm correct queue placement.
9. **Search screen** — date/folder filtering over Room data.
10. **Settings screen polish** — status counts, folder picker, re-pair flow, manual trigger.
11. **Packaging & real-world test** — jpackage installers for PC, signed APK for Android, full end-to-end run with a large real photo library (thousands of items) to validate performance assumptions under real Wi-Fi conditions.

---

## 11. iOS Client Architecture & Implementation

The iOS app (`ios/`) is a native Swift & SwiftUI application that fully replicates the Android client's behavior:

- **UI**: Pure SwiftUI, featuring:
  - **Photos Tab**: Date-grouped photo/video grid, live upload progress indicators, done checkmarks, video badges, multi-selection, and a fullscreen media viewer (pinch-to-zoom photo preview and AVPlayer video playback).
  - **Search Tab**: Real-time filename search with results grid and media viewer integration.
  - **Settings Tab**: Backup progress card, paired PC details, Pause/Resume toggle, Sync on Mobile via Tailscale toggle, and an Unpair action.
  - **Pairing View**: `AVFoundation` live camera QR code scanner with a "Pair Manually" fallback sheet.
- **Photo Engine**: `PhotoKit` (`PHPhotoLibrary`, `PHAsset`, `PHAssetResource`), including `PHPhotoLibraryChangeObserver` to automatically detect newly captured photos and videos in real time.
- **Discovery**: Apple's `Network` framework & Bonjour (`_photobackup._tcp`), plus multi-IP ping fallback (`GET /health`) covering LAN and Tailscale IPs.
- **Upload Engine**: Cryptographic SHA-256 via `CryptoKit`, pre-check `/exists`, and multipart/form-data upload with progress reporting and adaptive concurrency (4 photos / 2 videos on battery, 6 photos / 3 videos when plugged in and charging).
- **Data Persistence**: Native SQLite with WAL mode (`PRAGMA journal_mode=WAL;`), matching the Android Room and Desktop SQLite databases.

---

This plan is intended as a living reference — update it as decisions evolve during the build.
