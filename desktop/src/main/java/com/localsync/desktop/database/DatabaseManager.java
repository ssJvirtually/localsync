package com.localsync.desktop.database;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {
    private static final String DB_NAME = "localsync.db";
    private final String dbPath;

    public static class DeviceInfo {
        public String deviceId;
        public String deviceName;
        public String token;
        public long pairedAt;
        public int fileCount;
        public long totalBytes;
    }

    public DatabaseManager() {
        // Store database in the user's home directory (e.g. C:\Users\<Username>\.localsync)
        String dbFolder = System.getProperty("user.home") + File.separator + ".localsync";
        File dataDir = new File(dbFolder);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        this.dbPath = "jdbc:sqlite:" + dbFolder + File.separator + DB_NAME;
        initializeDatabase();
    }

    private Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(dbPath);
        try (Statement stmt = conn.createStatement()) {
            // Enable WAL mode
            stmt.execute("PRAGMA journal_mode=WAL;");
            // Set busy timeout to 5 seconds
            stmt.execute("PRAGMA busy_timeout=5000;");
        }
        return conn;
    }

    private void initializeDatabase() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            // Config table
            stmt.execute("CREATE TABLE IF NOT EXISTS config (" +
                    "key TEXT PRIMARY KEY, " +
                    "value TEXT" +
                    ");");

            // Devices table
            stmt.execute("CREATE TABLE IF NOT EXISTS devices (" +
                    "device_id TEXT PRIMARY KEY, " +
                    "device_name TEXT NOT NULL, " +
                    "token TEXT NOT NULL, " +
                    "paired_at INTEGER NOT NULL" +
                    ");");

            // Received files table
            stmt.execute("CREATE TABLE IF NOT EXISTS received_files (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "device_id TEXT NOT NULL, " +
                    "media_id INTEGER NOT NULL, " +
                    "file_hash TEXT NOT NULL, " +
                    "file_name TEXT NOT NULL, " +
                    "stored_path TEXT NOT NULL, " +
                    "size_bytes INTEGER NOT NULL, " +
                    "date_taken INTEGER NOT NULL, " +
                    "received_at INTEGER NOT NULL, " +
                    "FOREIGN KEY(device_id) REFERENCES devices(device_id)" +
                    ");");

            // Unique index to prevent duplicate backups per device
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_device_file_hash " +
                    "ON received_files(device_id, file_hash);");

            System.out.println("Database initialized successfully.");
        } catch (SQLException e) {
            System.err.println("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public synchronized String getConfig(String key, String defaultValue) {
        String sql = "SELECT value FROM config WHERE key = ?;";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        } catch (SQLException e) {
            System.err.println("Error reading config key " + key + ": " + e.getMessage());
        }
        return defaultValue;
    }

    public synchronized void setConfig(String key, String value) {
        String sql = "INSERT INTO config (key, value) VALUES (?, ?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value;";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            pstmt.setString(2, value);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error setting config key " + key + ": " + e.getMessage());
        }
    }

    public synchronized void registerDevice(String deviceId, String deviceName, String token) {
        String sql = "INSERT INTO devices (device_id, device_name, token, paired_at) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(device_id) DO UPDATE SET device_name = excluded.device_name, token = excluded.token;";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, deviceId);
            pstmt.setString(2, deviceName);
            pstmt.setString(3, token);
            pstmt.setLong(4, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error registering device: " + e.getMessage());
        }
    }

    public synchronized boolean isDeviceRegistered(String deviceId) {
        String sql = "SELECT 1 FROM devices WHERE device_id = ?;";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, deviceId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            System.err.println("Error checking if device exists: " + e.getMessage());
            return false;
        }
    }

    public synchronized void addReceivedFile(String deviceId, long mediaId, String hash, String fileName, 
                                             String storedPath, long sizeBytes, long dateTaken) throws SQLException {
        String sql = "INSERT OR IGNORE INTO received_files (device_id, media_id, file_hash, file_name, stored_path, size_bytes, date_taken, received_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?);";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, deviceId);
            pstmt.setLong(2, mediaId);
            pstmt.setString(3, hash);
            pstmt.setString(4, fileName);
            pstmt.setString(5, storedPath);
            pstmt.setLong(6, sizeBytes);
            pstmt.setLong(7, dateTaken);
            pstmt.setLong(8, System.currentTimeMillis());
            pstmt.executeUpdate();
        }
    }

    public synchronized boolean isFileExists(String deviceId, String hash) {
        String sql = "SELECT 1 FROM received_files WHERE device_id = ? AND file_hash = ?;";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, deviceId);
            pstmt.setString(2, hash);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            System.err.println("Error checking if file exists: " + e.getMessage());
            return false;
        }
    }

    public synchronized List<DeviceInfo> getDevices() {
        List<DeviceInfo> list = new ArrayList<>();
        String sql = "SELECT d.device_id, d.device_name, d.token, d.paired_at, " +
                "COUNT(r.id) as file_count, SUM(r.size_bytes) as total_bytes " +
                "FROM devices d LEFT JOIN received_files r ON d.device_id = r.device_id " +
                "GROUP BY d.device_id;";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                DeviceInfo info = new DeviceInfo();
                info.deviceId = rs.getString("device_id");
                info.deviceName = rs.getString("device_name");
                info.token = rs.getString("token");
                info.pairedAt = rs.getLong("paired_at");
                info.fileCount = rs.getInt("file_count");
                info.totalBytes = rs.getLong("total_bytes");
                list.add(info);
            }
        } catch (SQLException e) {
            System.err.println("Error fetching devices: " + e.getMessage());
        }
        return list;
    }

    public synchronized void deleteDevice(String deviceId) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Delete files associated with the device from database (retaining on disk is fine/expected, as backups are permanent)
                try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM received_files WHERE device_id = ?;")) {
                    pstmt.setString(1, deviceId);
                    pstmt.executeUpdate();
                }
                try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM devices WHERE device_id = ?;")) {
                    pstmt.setString(1, deviceId);
                    pstmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("Error deleting device " + deviceId + ": " + e.getMessage());
        }
    }
}
