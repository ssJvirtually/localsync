package com.localsync.desktop.server;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.UploadedFile;
import com.localsync.desktop.database.DatabaseManager;

import java.io.*;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

public class HttpServer {
    private final int port;
    private final String serverToken;
    private final DatabaseManager dbManager;
    private String backupDir;
    private Javalin app;
    private UploadListener uploadListener;

    public interface UploadListener {
        void onUploadReceived(String deviceId, String deviceName, String fileName, long sizeBytes);
    }

    public static class PairRequest {
        public String token;
        public String deviceName;
    }

    public HttpServer(int port, String serverToken, DatabaseManager dbManager, String backupDir) {
        this.port = port;
        this.serverToken = serverToken;
        this.dbManager = dbManager;
        this.backupDir = backupDir;
    }

    public void setBackupDir(String backupDir) {
        this.backupDir = backupDir;
    }

    public void setUploadListener(UploadListener listener) {
        this.uploadListener = listener;
    }

    public void start() {
        app = Javalin.create(config -> {
            config.jetty.multipartConfig.maxFileSize(4 * 1024 * 1024 * 1024L, io.javalin.config.SizeUnit.BYTES); // 4 GB max file size
            config.jetty.multipartConfig.maxTotalRequestSize(4 * 1024 * 1024 * 1024L, io.javalin.config.SizeUnit.BYTES);
        });

        // Global authorization check
        app.before(ctx -> {
            String path = ctx.path();
            if (path.equals("/health") || path.equals("/pair/verify")) {
                return;
            }

            String auth = ctx.header("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
                throw new UnauthorizedResponse("Missing or invalid Authorization header");
            }

            String requestToken = auth.substring(7);
            if (!requestToken.equals(serverToken)) {
                throw new UnauthorizedResponse("Invalid token");
            }
        });

        // Routes
        app.get("/health", this::handleHealth);
        app.post("/pair/verify", this::handlePairVerify);
        app.get("/exists", this::handleExists);
        app.post("/upload", this::handleUpload);
        app.get("/status", this::handleStatus);

        app.start(port);
        System.out.println("HTTP Server started on port " + port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
            System.out.println("HTTP Server stopped.");
        }
    }

    private void handleHealth(Context ctx) {
        ctx.json(Map.of("status", "ok"));
    }

    private void handlePairVerify(Context ctx) {
        PairRequest request = ctx.bodyAsClass(PairRequest.class);
        if (request.token == null || !request.token.equals(serverToken)) {
            ctx.status(HttpStatus.FORBIDDEN).json(Map.of("error", "Invalid pairing token"));
            return;
        }

        if (request.deviceName == null || request.deviceName.trim().isEmpty()) {
            request.deviceName = "Unknown Android Device";
        }

        // Generate or fetch a device ID for this paired device
        // Check if device is already registered by searching getDevices() or generated based on some client identifier.
        // For simple pairing, we assign a new UUID. If the client sends its own device UUID, we would use it, 
        // but for now, we generate a fresh deviceId and return it.
        String deviceId = UUID.randomUUID().toString();
        dbManager.registerDevice(deviceId, request.deviceName, serverToken);

        ctx.json(Map.of("deviceId", deviceId));
        System.out.println("Paired successfully with device: " + request.deviceName + " (" + deviceId + ")");
    }

    private void handleExists(Context ctx) {
        String hash = ctx.queryParam("hash");
        String deviceId = ctx.queryParam("deviceId");

        if (hash == null || deviceId == null) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Missing hash or deviceId"));
            return;
        }

        boolean exists = dbManager.isFileExists(deviceId, hash);
        ctx.json(Map.of("exists", exists));
    }

    private void handleUpload(Context ctx) {
        UploadedFile file = ctx.uploadedFile("file");
        String mediaIdStr = ctx.formParam("mediaId");
        String hash = ctx.formParam("hash");
        String dateTakenStr = ctx.formParam("dateTaken");
        String fileName = ctx.formParam("fileName");
        String deviceId = ctx.formParam("deviceId");

        if (file == null || mediaIdStr == null || hash == null || dateTakenStr == null || fileName == null || deviceId == null) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Missing multipart form parameters"));
            return;
        }

        if (!dbManager.isDeviceRegistered(deviceId)) {
            ctx.status(HttpStatus.FORBIDDEN).json(Map.of("error", "Device is not paired"));
            return;
        }

        long mediaId = Long.parseLong(mediaIdStr);
        long dateTaken = Long.parseLong(dateTakenStr);

        // Check if the file already exists in SQLite
        if (dbManager.isFileExists(deviceId, hash)) {
            ctx.json(Map.of("status", "success", "hash", hash, "receivedAt", System.currentTimeMillis(), "message", "Already backed up"));
            return;
        }

        File deviceDir = new File(backupDir, deviceId);
        if (!deviceDir.exists()) {
            deviceDir.mkdirs();
        }

        // Deal with filename collision
        File targetFile = new File(deviceDir, fileName);
        if (targetFile.exists()) {
            // Append short hash to filename to prevent overwrite
            String name = getBaseName(fileName);
            String ext = getExtension(fileName);
            String shortHash = hash.length() > 8 ? hash.substring(0, 8) : hash;
            targetFile = new File(deviceDir, name + "_" + shortHash + "." + ext);
        }

        // Stream file contents into local destination
        try (InputStream in = new BufferedInputStream(file.content());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(targetFile))) {
            
            byte[] buffer = new byte[16384]; // 16 KB buffer
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();

            // Save to DB
            dbManager.addReceivedFile(deviceId, mediaId, hash, targetFile.getName(), targetFile.getAbsolutePath(), file.size(), dateTaken);

            // Notify listener (for UI updates)
            if (uploadListener != null) {
                String deviceName = dbManager.getDevices().stream()
                        .filter(d -> d.deviceId.equals(deviceId))
                        .map(d -> d.deviceName)
                        .findFirst()
                        .orElse("Unknown Device");
                uploadListener.onUploadReceived(deviceId, deviceName, targetFile.getName(), file.size());
            }

            ctx.json(Map.of("status", "success", "hash", hash, "receivedAt", System.currentTimeMillis()));
            System.out.println("Saved file " + targetFile.getName() + " for device " + deviceId);

        } catch (IOException e) {
            // Clean up partial file on failure
            if (targetFile.exists()) {
                targetFile.delete();
            }
            System.err.println("Error saving file: " + e.getMessage());
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", "Failed to save file: " + e.getMessage()));
        } catch (SQLException e) {
            // Database registration failed, clean up file
            if (targetFile.exists()) {
                targetFile.delete();
            }
            System.err.println("DB error registering file: " + e.getMessage());
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", "Failed to register file in DB: " + e.getMessage()));
        }
    }

    private void handleStatus(Context ctx) {
        String deviceId = ctx.queryParam("deviceId");
        if (deviceId == null) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Missing deviceId"));
            return;
        }

        var devices = dbManager.getDevices();
        for (var device : devices) {
            if (device.deviceId.equals(deviceId)) {
                ctx.json(Map.of(
                        "filesReceived", device.fileCount,
                        "totalBytes", device.totalBytes
                ));
                return;
            }
        }
        ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Device not found"));
    }

    private String getBaseName(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index == -1) return fileName;
        return fileName.substring(0, index);
    }

    private String getExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index == -1) return "";
        return fileName.substring(index + 1);
    }
}
