package com.localsync.desktop.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localsync.desktop.database.DatabaseManager;
import com.localsync.desktop.discovery.DiscoveryManager;
import com.localsync.desktop.server.HttpServer;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MainFxApp extends Application {
    private DatabaseManager dbManager;
    private DiscoveryManager discoveryManager;
    private HttpServer httpServer;

    private String serverToken;
    private String pcName;
    private int port;
    private String backupDir;

    private StackPane contentArea;
    private ObservableList<DatabaseManager.DeviceInfo> devicesList = FXCollections.observableArrayList();
    private TableView<DatabaseManager.DeviceInfo> devicesTable;
    private TextArea logArea;
    private ImageView qrImageView;
    private Label qrTextLabel;

    private Button btnPairing;
    private Button btnDevices;
    private Button btnLogs;
    private Button btnSettings;

    @Override
    public void start(Stage primaryStage) {
        // Initialize Core Components
        dbManager = new DatabaseManager();
        initConfiguration();

        // Start Services
        startServices();

        // Build UI
        primaryStage.setTitle("LocalSync Backup PC Server");
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);

        BorderPane mainLayout = new BorderPane();

        // 1. Sidebar Panel
        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(220);

        Text titleText = new Text("LocalSync");
        titleText.getStyleClass().add("sidebar-title");
        VBox.setMargin(titleText, new Insets(0, 0, 20, 0));

        btnPairing = new Button("Pairing QR Code");
        btnPairing.getStyleClass().addAll("sidebar-btn", "sidebar-btn-active");
        btnPairing.setOnAction(e -> switchView("pairing"));

        btnDevices = new Button("Connected Devices");
        btnDevices.getStyleClass().add("sidebar-btn");
        btnDevices.setOnAction(e -> {
            refreshDevicesList();
            switchView("devices");
        });

        btnLogs = new Button("Activity Log");
        btnLogs.getStyleClass().add("sidebar-btn");
        btnLogs.setOnAction(e -> switchView("logs"));

        btnSettings = new Button("Settings");
        btnSettings.getStyleClass().add("sidebar-btn");
        btnSettings.setOnAction(e -> switchView("settings"));

        sidebar.getChildren().addAll(titleText, btnPairing, btnDevices, btnLogs, btnSettings);
        mainLayout.setLeft(sidebar);

        // 2. Main Content Area
        contentArea = new StackPane();
        contentArea.getStyleClass().add("main-content");

        // Views initialization
        VBox pairingView = createPairingView();
        VBox devicesView = createDevicesView();
        VBox logsView = createLogsView();
        VBox settingsView = createSettingsView();

        contentArea.getChildren().addAll(pairingView, devicesView, logsView, settingsView);

        // Show pairing view by default
        pairingView.setVisible(true);
        devicesView.setVisible(false);
        logsView.setVisible(false);
        settingsView.setVisible(false);

        mainLayout.setCenter(contentArea);

        Scene scene = new Scene(mainLayout, 960, 650);
        String cssPath = getClass().getResource("/style.css") != null 
                ? getClass().getResource("/style.css").toExternalForm() 
                : "file:src/main/resources/style.css";
        scene.getStylesheets().add(cssPath);

        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> stopServices());
        primaryStage.show();

        log("System started. PC Name: " + pcName + ", Port: " + port);
    }

    private void initConfiguration() {
        // Load or generate PC Token
        serverToken = dbManager.getConfig("server_token", null);
        if (serverToken == null) {
            serverToken = UUID.randomUUID().toString();
            dbManager.setConfig("server_token", serverToken);
        }

        // Hostname
        String defaultPcName = "My PC";
        try {
            defaultPcName = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            // ignore
        }
        pcName = dbManager.getConfig("pc_name", defaultPcName);

        // Port find
        String savedPort = dbManager.getConfig("port", null);
        if (savedPort != null) {
            port = Integer.parseInt(savedPort);
        } else {
            port = findFreePort(8080);
            dbManager.setConfig("port", String.valueOf(port));
        }

        // Backup directory
        String defaultBackupDir = System.getProperty("user.home") + File.separator + "localsync";
        backupDir = dbManager.getConfig("backup_dir", defaultBackupDir);
        dbManager.setConfig("backup_dir", backupDir);
        File dir = new File(backupDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private int findFreePort(int startPort) {
        int currentPort = startPort;
        while (currentPort < startPort + 100) {
            try (ServerSocket ignored = new ServerSocket(currentPort)) {
                return currentPort;
            } catch (Exception e) {
                currentPort++;
            }
        }
        return 8080; // Fallback
    }

    private void startServices() {
        // Start mDNS
        discoveryManager = new DiscoveryManager(pcName, port);
        new Thread(discoveryManager::startAdvertising).start();

        // Start HTTP Server
        httpServer = new HttpServer(port, serverToken, dbManager, backupDir);
        httpServer.setUploadListener((deviceId, deviceName, fileName, sizeBytes) -> {
            Platform.runLater(() -> {
                log("Upload completed: [" + deviceName + "] " + fileName + " (" + humanReadableByteCountBin(sizeBytes) + ")");
                refreshDevicesList();
            });
        });
        new Thread(httpServer::start).start();
    }

    private void stopServices() {
        if (discoveryManager != null) {
            discoveryManager.stopAdvertising();
        }
        if (httpServer != null) {
            httpServer.stop();
        }
    }

    private void switchView(String viewName) {
        btnPairing.getStyleClass().remove("sidebar-btn-active");
        btnDevices.getStyleClass().remove("sidebar-btn-active");
        btnLogs.getStyleClass().remove("sidebar-btn-active");
        btnSettings.getStyleClass().remove("sidebar-btn-active");

        contentArea.getChildren().forEach(node -> node.setVisible(false));

        if (viewName.equals("pairing")) {
            btnPairing.getStyleClass().add("sidebar-btn-active");
            contentArea.getChildren().get(0).setVisible(true);
        } else if (viewName.equals("devices")) {
            btnDevices.getStyleClass().add("sidebar-btn-active");
            contentArea.getChildren().get(1).setVisible(true);
        } else if (viewName.equals("logs")) {
            btnLogs.getStyleClass().add("sidebar-btn-active");
            contentArea.getChildren().get(2).setVisible(true);
        } else if (viewName.equals("settings")) {
            btnSettings.getStyleClass().add("sidebar-btn-active");
            contentArea.getChildren().get(3).setVisible(true);
        }
    }

    private VBox createPairingView() {
        VBox vbox = new VBox();
        vbox.getStyleClass().add("card");
        vbox.setAlignment(Pos.CENTER);
        vbox.setSpacing(20);

        Text title = new Text("Pair Your Phone");
        title.getStyleClass().add("title-text");

        Text desc = new Text("Scan this QR code in the LocalSync Android application to pair the device.");
        desc.getStyleClass().add("body-text");

        qrImageView = new ImageView();
        qrImageView.setFitWidth(280);
        qrImageView.setFitHeight(280);
        qrImageView.setPreserveRatio(true);

        qrTextLabel = new Label();
        qrTextLabel.getStyleClass().add("code-text");

        Button btnRegen = new Button("Regenerate Pair Token");
        btnRegen.getStyleClass().add("danger-btn");
        btnRegen.setOnAction(e -> regenerateToken());

        Button btnCopy = new Button("Copy Pairing Info");
        btnCopy.getStyleClass().add("primary-btn");
        btnCopy.setOnAction(e -> {
            try {
                List<String> allIps = DiscoveryManager.getAllLocalIpAddresses();
                InetAddress localAddr = DiscoveryManager.getLocalIpAddress();
                String localIp = localAddr != null ? localAddr.getHostAddress() : "127.0.0.1";
                if (!allIps.contains(localIp)) {
                    allIps.add(0, localIp);
                }

                Map<String, Object> qrData = new HashMap<>();
                qrData.put("service", "_photobackup._tcp.local.");
                qrData.put("token", serverToken);
                qrData.put("pcName", pcName);
                qrData.put("port", port);
                qrData.put("localIp", localIp);
                qrData.put("ips", allIps);

                ObjectMapper mapper = new ObjectMapper();
                String qrJson = mapper.writeValueAsString(qrData);

                javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(qrJson);
                clipboard.setContent(content);

                log("Pairing information copied to clipboard.");
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Info Copied");
                alert.setHeaderText(null);
                alert.setContentText("Pairing information JSON successfully copied to clipboard! You can paste it into the Android 'Pair Manually' dialog.");
                alert.showAndWait();
            } catch (Exception ex) {
                log("Error copying pairing info: " + ex.getMessage());
            }
        });

        HBox btnBox = new HBox(15);
        btnBox.setAlignment(Pos.CENTER);
        btnBox.getChildren().addAll(btnCopy, btnRegen);

        vbox.getChildren().addAll(title, desc, qrImageView, qrTextLabel, btnBox);

        // Initial QR Generate
        updateQrCode();

        return vbox;
    }

    private void regenerateToken() {
        serverToken = UUID.randomUUID().toString();
        dbManager.setConfig("server_token", serverToken);
        
        // Stop server and advertiser
        stopServices();
        
        // Re-read settings and restart
        initConfiguration();
        startServices();
        
        // Update view
        updateQrCode();
        log("Security pairing token regenerated. Connections reinitialized.");
        
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Token Regenerated");
        alert.setHeaderText(null);
        alert.setContentText("The pairing token was successfully regenerated. Existing devices will remain paired, but new devices must use the new QR code.");
        alert.showAndWait();
    }

    private void updateQrCode() {
        try {
            List<String> allIps = DiscoveryManager.getAllLocalIpAddresses();
            InetAddress localAddr = DiscoveryManager.getLocalIpAddress();
            String localIp = localAddr != null ? localAddr.getHostAddress() : "127.0.0.1";
            if (!allIps.contains(localIp)) {
                allIps.add(0, localIp);
            }

            Map<String, Object> qrData = new HashMap<>();
            qrData.put("service", "_photobackup._tcp.local.");
            qrData.put("token", serverToken);
            qrData.put("pcName", pcName);
            qrData.put("port", port);
            qrData.put("localIp", localIp);
            qrData.put("ips", allIps);

            ObjectMapper mapper = new ObjectMapper();
            String qrJson = mapper.writeValueAsString(qrData);

            qrImageView.setImage(QrCodeGenerator.generateQrCode(qrJson, 300, 300));

            // Find Tailscale IP (starts with 100. and fits range)
            String tailscaleIp = "";
            for (String ip : allIps) {
                if (ip.startsWith("100.")) {
                    String[] parts = ip.split("\\.");
                    if (parts.length >= 2) {
                        try {
                            int second = Integer.parseInt(parts[1]);
                            if (second >= 64 && second <= 127) {
                                tailscaleIp = ip;
                                break;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            String infoText = "Server LAN IP: " + localIp;
            if (!tailscaleIp.isEmpty()) {
                infoText += "  |  Tailscale IP: " + tailscaleIp;
            }
            infoText += "  |  Port: " + port + "  |  Token: " + serverToken.substring(0, 8) + "...";
            qrTextLabel.setText(infoText);
        } catch (Exception e) {
            log("Error generating pairing QR code: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private VBox createDevicesView() {
        VBox vbox = new VBox();
        vbox.getStyleClass().add("card");
        vbox.setSpacing(20);

        Text title = new Text("Connected Devices");
        title.getStyleClass().add("title-text");

        devicesTable = new TableView<>();
        devicesTable.setPlaceholder(new Label("No devices paired yet. Scan the pairing QR code to connect."));

        TableColumn<DatabaseManager.DeviceInfo, String> colName = new TableColumn<>("Device Name");
        colName.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().deviceName));
        colName.setPrefWidth(180);

        TableColumn<DatabaseManager.DeviceInfo, String> colId = new TableColumn<>("Device ID");
        colId.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().deviceId));
        colId.setPrefWidth(260);

        TableColumn<DatabaseManager.DeviceInfo, String> colPaired = new TableColumn<>("Paired Date");
        colPaired.setCellValueFactory(data -> {
            LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(data.getValue().pairedAt), ZoneId.systemDefault());
            return new SimpleStringProperty(dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        });
        colPaired.setPrefWidth(150);

        TableColumn<DatabaseManager.DeviceInfo, String> colCount = new TableColumn<>("Files Synced");
        colCount.setCellValueFactory(data -> new SimpleStringProperty(String.valueOf(data.getValue().fileCount)));
        colCount.setPrefWidth(100);

        TableColumn<DatabaseManager.DeviceInfo, String> colSize = new TableColumn<>("Total Storage");
        colSize.setCellValueFactory(data -> new SimpleStringProperty(humanReadableByteCountBin(data.getValue().totalBytes)));
        colSize.setPrefWidth(120);

        devicesTable.getColumns().addAll(colName, colId, colPaired, colCount, colSize);
        devicesTable.setItems(devicesList);

        Button btnDelete = new Button("Unpair Device");
        btnDelete.getStyleClass().add("danger-btn");
        btnDelete.setOnAction(e -> {
            DatabaseManager.DeviceInfo selected = devicesTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, 
                        "Are you sure you want to unpair " + selected.deviceName + "? This won't delete backed-up files on disk, but the device won't be able to sync again without re-pairing.", 
                        ButtonType.YES, ButtonType.NO);
                confirm.setTitle("Confirm Unpair");
                confirm.setHeaderText(null);
                confirm.showAndWait().ifPresent(response -> {
                    if (response == ButtonType.YES) {
                        dbManager.deleteDevice(selected.deviceId);
                        log("Unpaired device: " + selected.deviceName + " (" + selected.deviceId + ")");
                        refreshDevicesList();
                    }
                });
            }
        });

        vbox.getChildren().addAll(title, devicesTable, btnDelete);
        VBox.setVgrow(devicesTable, Priority.ALWAYS);

        return vbox;
    }

    private void refreshDevicesList() {
        devicesList.clear();
        devicesList.addAll(dbManager.getDevices());
    }

    private VBox createLogsView() {
        VBox vbox = new VBox();
        vbox.getStyleClass().add("card");
        vbox.setSpacing(15);

        Text title = new Text("Activity Log");
        title.getStyleClass().add("title-text");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(450);
        logArea.getStyleClass().add("text-area");

        Button btnClear = new Button("Clear Logs");
        btnClear.getStyleClass().add("primary-btn");
        btnClear.setOnAction(e -> logArea.clear());

        vbox.getChildren().addAll(title, logArea, btnClear);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        return vbox;
    }

    private void log(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String logLine = "[" + timestamp + "] " + message + "\n";
        System.out.print(logLine);
        if (logArea != null) {
            Platform.runLater(() -> logArea.appendText(logLine));
        }
    }

    public static String humanReadableByteCountBin(long bytes) {
        long absBytes = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
        if (absBytes < 1024) {
            return bytes + " B";
        }
        CharacterIterator ci = new StringCharacterIterator("KMGTPE");
        long value = absBytes;
        for (int i = 0; i < 6; i++) {
            if (value < 999_950) {
                break;
            }
            value /= 1024;
            ci.next();
        }
        value *= Long.signum(bytes);
        return String.format("%.2f %ciB", value / 1024.0, ci.current());
    }

    private VBox createSettingsView() {
        VBox vbox = new VBox();
        vbox.getStyleClass().add("card");
        vbox.setSpacing(20);

        Text title = new Text("Settings");
        title.getStyleClass().add("title-text");

        Label lblBackupDir = new Label("Backup Directory:");
        lblBackupDir.getStyleClass().add("body-text");

        TextField txtBackupDir = new TextField(backupDir);
        txtBackupDir.setEditable(false);
        txtBackupDir.setPrefWidth(500);
        txtBackupDir.getStyleClass().add("text-field");

        Button btnBrowse = new Button("Browse...");
        btnBrowse.getStyleClass().add("secondary-btn");
        btnBrowse.setOnAction(e -> {
            javafx.stage.DirectoryChooser directoryChooser = new javafx.stage.DirectoryChooser();
            directoryChooser.setTitle("Select Backup Directory");
            File currentDir = new File(txtBackupDir.getText());
            if (currentDir.exists() && currentDir.isDirectory()) {
                directoryChooser.setInitialDirectory(currentDir);
            }
            File selectedDirectory = directoryChooser.showDialog(txtBackupDir.getScene().getWindow());
            if (selectedDirectory != null) {
                txtBackupDir.setText(selectedDirectory.getAbsolutePath());
            }
        });

        HBox pathBox = new HBox(10);
        pathBox.getChildren().addAll(txtBackupDir, btnBrowse);

        Button btnSave = new Button("Save Settings");
        btnSave.getStyleClass().add("primary-btn");
        btnSave.setOnAction(e -> {
            String newPath = txtBackupDir.getText();
            if (newPath != null && !newPath.trim().isEmpty()) {
                File newDir = new File(newPath);
                if (!newDir.exists()) {
                    newDir.mkdirs();
                }
                dbManager.setConfig("backup_dir", newPath);
                backupDir = newPath;
                if (httpServer != null) {
                    httpServer.setBackupDir(newPath);
                }
                log("Backup directory updated to: " + newPath);
                
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Settings Saved");
                alert.setHeaderText(null);
                alert.setContentText("The backup directory was successfully updated!");
                alert.showAndWait();
            }
        });

        vbox.getChildren().addAll(title, lblBackupDir, pathBox, btnSave);
        return vbox;
    }
}

