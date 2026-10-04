import Foundation
import SQLite3

public final class DatabaseManager {
    public static let shared = DatabaseManager()

    private var db: OpaquePointer?
    private let queue = DispatchQueue(label: "com.localsync.database", qos: .userInitiated)

    public init() {
        openDatabase()
        createTables()
    }

    deinit {
        if db != nil {
            sqlite3_close(db)
        }
    }

    private func openDatabase() {
        let fileManager = FileManager.default
        guard let appSupportDir = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            fatalError("Unable to locate application support directory")
        }

        if !fileManager.fileExists(atPath: appSupportDir.path) {
            try? fileManager.createDirectory(at: appSupportDir, withIntermediateDirectories: true)
        }

        let dbPath = appSupportDir.appendingPathComponent("localsync.sqlite").path

        if sqlite3_open(dbPath, &db) == SQLITE_OK {
            // Enable WAL mode for high concurrency matching desktop and android
            var errMsg: UnsafeMutablePointer<CChar>?
            sqlite3_exec(db, "PRAGMA journal_mode=WAL;", nil, nil, &errMsg)
            sqlite3_exec(db, "PRAGMA synchronous=NORMAL;", nil, nil, &errMsg)
        } else {
            print("Error opening database at \(dbPath)")
        }
    }

    private func createTables() {
        let createServers = """
        CREATE TABLE IF NOT EXISTS paired_servers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            service_name TEXT NOT NULL,
            pc_name TEXT NOT NULL,
            token TEXT NOT NULL,
            device_id TEXT NOT NULL,
            fallback_ip TEXT NOT NULL,
            paired_at INTEGER NOT NULL
        );
        """

        let createMedia = """
        CREATE TABLE IF NOT EXISTS media_items (
            media_id TEXT PRIMARY KEY,
            file_path TEXT NOT NULL,
            file_name TEXT NOT NULL,
            date_taken INTEGER NOT NULL,
            file_hash TEXT,
            size_bytes INTEGER NOT NULL,
            media_type TEXT NOT NULL,
            backup_status TEXT NOT NULL,
            last_attempt_at INTEGER,
            paired_server_id INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_media_status_date ON media_items (backup_status, date_taken ASC);
        """

        let createSettings = """
        CREATE TABLE IF NOT EXISTS app_settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        """

        queue.sync {
            var errMsg: UnsafeMutablePointer<CChar>?
            sqlite3_exec(db, createServers, nil, nil, &errMsg)
            sqlite3_exec(db, createMedia, nil, nil, &errMsg)
            sqlite3_exec(db, createSettings, nil, nil, &errMsg)
        }
    }

    // MARK: - Paired Server Operations

    public func getPairedServer() -> PairedServer? {
        queue.sync {
            let query = "SELECT id, service_name, pc_name, token, device_id, fallback_ip, paired_at FROM paired_servers LIMIT 1;"
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            if sqlite3_prepare_v2(db, query, -1, &stmt, nil) == SQLITE_OK {
                if sqlite3_step(stmt) == SQLITE_ROW {
                    let id = sqlite3_column_int64(stmt, 0)
                    let service = String(cString: sqlite3_column_text(stmt, 1))
                    let pcName = String(cString: sqlite3_column_text(stmt, 2))
                    let token = String(cString: sqlite3_column_text(stmt, 3))
                    let deviceId = String(cString: sqlite3_column_text(stmt, 4))
                    let fallbackIp = String(cString: sqlite3_column_text(stmt, 5))
                    let pairedAt = sqlite3_column_int64(stmt, 6)

                    return PairedServer(
                        id: id,
                        serviceName: service,
                        pcName: pcName,
                        token: token,
                        deviceId: deviceId,
                        fallbackIp: fallbackIp,
                        pairedAt: pairedAt
                    )
                }
            }
            return nil
        }
    }

    public func insertPairedServer(_ server: PairedServer) -> Int64 {
        queue.sync {
            let sql = "INSERT INTO paired_servers (service_name, pc_name, token, device_id, fallback_ip, paired_at) VALUES (?, ?, ?, ?, ?, ?);"
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_text(stmt, 1, (server.serviceName as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 2, (server.pcName as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 3, (server.token as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 4, (server.deviceId as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 5, (server.fallbackIp as NSString).utf8String, -1, nil)
                sqlite3_bind_int64(stmt, 6, server.pairedAt)

                if sqlite3_step(stmt) == SQLITE_DONE {
                    return sqlite3_last_insert_rowid(db)
                }
            }
            return 1
        }
    }

    public func updatePairedServerFallbackIp(id: Int64, fallbackIp: String) {
        queue.sync {
            let sql = "UPDATE paired_servers SET fallback_ip = ? WHERE id = ?;"
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_text(stmt, 1, (fallbackIp as NSString).utf8String, -1, nil)
                sqlite3_bind_int64(stmt, 2, id)
                sqlite3_step(stmt)
            }
        }
    }

    public func unpair() {
        queue.sync {
            var errMsg: UnsafeMutablePointer<CChar>?
            sqlite3_exec(db, "DELETE FROM media_items;", nil, nil, &errMsg)
            sqlite3_exec(db, "DELETE FROM paired_servers;", nil, nil, &errMsg)
        }
    }

    // MARK: - Media Items Operations

    public func getAllMediaItems() -> [MediaItem] {
        queue.sync {
            let query = """
            SELECT media_id, file_path, file_name, date_taken, file_hash, size_bytes, media_type, backup_status, last_attempt_at, paired_server_id
            FROM media_items
            ORDER BY date_taken DESC;
            """
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            var items: [MediaItem] = []
            if sqlite3_prepare_v2(db, query, -1, &stmt, nil) == SQLITE_OK {
                while sqlite3_step(stmt) == SQLITE_ROW {
                    let mediaId = String(cString: sqlite3_column_text(stmt, 0))
                    let filePath = String(cString: sqlite3_column_text(stmt, 1))
                    let fileName = String(cString: sqlite3_column_text(stmt, 2))
                    let dateTaken = sqlite3_column_int64(stmt, 3)
                    let fileHash = sqlite3_column_text(stmt, 4).map { String(cString: $0) }
                    let sizeBytes = sqlite3_column_int64(stmt, 5)
                    let mediaType = MediaType(rawValue: String(cString: sqlite3_column_text(stmt, 6))) ?? .photo
                    let backupStatus = BackupStatus(rawValue: String(cString: sqlite3_column_text(stmt, 7))) ?? .pending
                    let lastAttemptAt = sqlite3_column_type(stmt, 8) != SQLITE_NULL ? sqlite3_column_int64(stmt, 8) : nil
                    let pairedServerId = sqlite3_column_int64(stmt, 9)

                    items.append(MediaItem(
                        mediaId: mediaId,
                        filePath: filePath,
                        fileName: fileName,
                        dateTaken: dateTaken,
                        fileHash: fileHash,
                        sizeBytes: sizeBytes,
                        mediaType: mediaType,
                        backupStatus: backupStatus,
                        lastAttemptAt: lastAttemptAt,
                        pairedServerId: pairedServerId
                    ))
                }
            }
            return items
        }
    }

    public func getPendingMediaItems(limit: Int = 30) -> [MediaItem] {
        queue.sync {
            let query = """
            SELECT media_id, file_path, file_name, date_taken, file_hash, size_bytes, media_type, backup_status, last_attempt_at, paired_server_id
            FROM media_items
            WHERE backup_status = 'PENDING'
            ORDER BY date_taken ASC
            LIMIT ?;
            """
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            var items: [MediaItem] = []
            if sqlite3_prepare_v2(db, query, -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_int(stmt, 1, Int32(limit))
                while sqlite3_step(stmt) == SQLITE_ROW {
                    let mediaId = String(cString: sqlite3_column_text(stmt, 0))
                    let filePath = String(cString: sqlite3_column_text(stmt, 1))
                    let fileName = String(cString: sqlite3_column_text(stmt, 2))
                    let dateTaken = sqlite3_column_int64(stmt, 3)
                    let fileHash = sqlite3_column_text(stmt, 4).map { String(cString: $0) }
                    let sizeBytes = sqlite3_column_int64(stmt, 5)
                    let mediaType = MediaType(rawValue: String(cString: sqlite3_column_text(stmt, 6))) ?? .photo
                    let backupStatus = BackupStatus(rawValue: String(cString: sqlite3_column_text(stmt, 7))) ?? .pending
                    let lastAttemptAt = sqlite3_column_type(stmt, 8) != SQLITE_NULL ? sqlite3_column_int64(stmt, 8) : nil
                    let pairedServerId = sqlite3_column_int64(stmt, 9)

                    items.append(MediaItem(
                        mediaId: mediaId,
                        filePath: filePath,
                        fileName: fileName,
                        dateTaken: dateTaken,
                        fileHash: fileHash,
                        sizeBytes: sizeBytes,
                        mediaType: mediaType,
                        backupStatus: backupStatus,
                        lastAttemptAt: lastAttemptAt,
                        pairedServerId: pairedServerId
                    ))
                }
            }
            return items
        }
    }

    public func insertOrIgnoreMediaItems(_ items: [MediaItem]) {
        guard !items.isEmpty else { return }
        queue.sync {
            sqlite3_exec(db, "BEGIN TRANSACTION;", nil, nil, nil)

            let sql = """
            INSERT OR IGNORE INTO media_items
            (media_id, file_path, file_name, date_taken, file_hash, size_bytes, media_type, backup_status, last_attempt_at, paired_server_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
            """
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                for item in items {
                    sqlite3_bind_text(stmt, 1, (item.mediaId as NSString).utf8String, -1, nil)
                    sqlite3_bind_text(stmt, 2, (item.filePath as NSString).utf8String, -1, nil)
                    sqlite3_bind_text(stmt, 3, (item.fileName as NSString).utf8String, -1, nil)
                    sqlite3_bind_int64(stmt, 4, item.dateTaken)
                    if let hash = item.fileHash {
                        sqlite3_bind_text(stmt, 5, (hash as NSString).utf8String, -1, nil)
                    } else {
                        sqlite3_bind_null(stmt, 5)
                    }
                    sqlite3_bind_int64(stmt, 6, item.sizeBytes)
                    sqlite3_bind_text(stmt, 7, (item.mediaType.rawValue as NSString).utf8String, -1, nil)
                    sqlite3_bind_text(stmt, 8, (item.backupStatus.rawValue as NSString).utf8String, -1, nil)
                    if let attempt = item.lastAttemptAt {
                        sqlite3_bind_int64(stmt, 9, attempt)
                    } else {
                        sqlite3_bind_null(stmt, 9)
                    }
                    sqlite3_bind_int64(stmt, 10, item.pairedServerId)

                    sqlite3_step(stmt)
                    sqlite3_reset(stmt)
                }
            }
            sqlite3_exec(db, "COMMIT;", nil, nil, nil)
        }
    }

    public func updateItemStatus(mediaId: String, status: BackupStatus, hash: String? = nil) {
        queue.sync {
            let sql: String
            if let hash = hash {
                sql = "UPDATE media_items SET backup_status = ?, file_hash = ? WHERE media_id = ?;"
                var stmt: OpaquePointer?
                defer { sqlite3_finalize(stmt) }
                if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                    sqlite3_bind_text(stmt, 1, (status.rawValue as NSString).utf8String, -1, nil)
                    sqlite3_bind_text(stmt, 2, (hash as NSString).utf8String, -1, nil)
                    sqlite3_bind_text(stmt, 3, (mediaId as NSString).utf8String, -1, nil)
                    sqlite3_step(stmt)
                }
            } else {
                sql = "UPDATE media_items SET backup_status = ? WHERE media_id = ?;"
                var stmt: OpaquePointer?
                defer { sqlite3_finalize(stmt) }
                if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                    sqlite3_bind_text(stmt, 1, (status.rawValue as NSString).utf8String, -1, nil)
                    sqlite3_bind_text(stmt, 2, (mediaId as NSString).utf8String, -1, nil)
                    sqlite3_step(stmt)
                }
            }
        }
    }

    public func recordFailedAttempt(mediaId: String) {
        queue.sync {
            let sql = "UPDATE media_items SET backup_status = 'PENDING', last_attempt_at = ? WHERE media_id = ?;"
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }
            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_int64(stmt, 1, Int64(Date().timeIntervalSince1970 * 1000))
                sqlite3_bind_text(stmt, 2, (mediaId as NSString).utf8String, -1, nil)
                sqlite3_step(stmt)
            }
        }
    }

    public func resetUploadingStatus() {
        queue.sync {
            var errMsg: UnsafeMutablePointer<CChar>?
            sqlite3_exec(db, "UPDATE media_items SET backup_status = 'PENDING' WHERE backup_status = 'UPLOADING';", nil, nil, &errMsg)
        }
    }

    public func getCounts() -> (total: Int, backedUp: Int) {
        queue.sync {
            var total = 0
            var backedUp = 0

            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM media_items;", -1, &stmt, nil) == SQLITE_OK {
                if sqlite3_step(stmt) == SQLITE_ROW {
                    total = Int(sqlite3_column_int(stmt, 0))
                }
            }
            sqlite3_finalize(stmt)

            if sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM media_items WHERE backup_status = 'DONE';", -1, &stmt, nil) == SQLITE_OK {
                if sqlite3_step(stmt) == SQLITE_ROW {
                    backedUp = Int(sqlite3_column_int(stmt, 0))
                }
            }
            sqlite3_finalize(stmt)

            return (total, backedUp)
        }
    }

    public func deleteMediaItems(ids: [String]) {
        guard !ids.isEmpty else { return }
        queue.sync {
            sqlite3_exec(db, "BEGIN TRANSACTION;", nil, nil, nil)
            let sql = "DELETE FROM media_items WHERE media_id = ?;"
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                for id in ids {
                    sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
                    sqlite3_step(stmt)
                    sqlite3_reset(stmt)
                }
            }
            sqlite3_exec(db, "COMMIT;", nil, nil, nil)
        }
    }

    // MARK: - App Settings

    public func isSyncPaused() -> Bool {
        return getSetting(key: "sync_paused") == "true"
    }

    public func setSyncPaused(_ paused: Bool) {
        setSetting(key: "sync_paused", value: paused ? "true" : "false")
    }

    public func isSyncOnCellularTailscale() -> Bool {
        return getSetting(key: "sync_on_cellular_tailscale") == "true"
    }

    public func setSyncOnCellularTailscale(_ enabled: Bool) {
        setSetting(key: "sync_on_cellular_tailscale", value: enabled ? "true" : "false")
    }

    private func getSetting(key: String) -> String? {
        queue.sync {
            let sql = "SELECT value FROM app_settings WHERE key = ? LIMIT 1;"
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_text(stmt, 1, (key as NSString).utf8String, -1, nil)
                if sqlite3_step(stmt) == SQLITE_ROW {
                    return String(cString: sqlite3_column_text(stmt, 0))
                }
            }
            return nil
        }
    }

    private func setSetting(key: String, value: String) {
        queue.sync {
            let sql = "INSERT OR REPLACE INTO app_settings (key, value) VALUES (?, ?);"
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }

            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_text(stmt, 1, (key as NSString).utf8String, -1, nil)
                sqlite3_bind_text(stmt, 2, (value as NSString).utf8String, -1, nil)
                sqlite3_step(stmt)
            }
        }
    }
}
