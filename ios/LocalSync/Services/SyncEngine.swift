import Foundation
import UIKit
import Combine

public final class SyncEngine: ObservableObject {
    public static let shared = SyncEngine()

    @Published public private(set) var isSyncing: Bool = false
    @Published public private(set) var uploadProgress: [String: Double] = [:]
    @Published public private(set) var statusMessage: String = "Idle"

    private var syncTask: Task<Void, Never>?
    private var cancellables = Set<AnyCancellable>()

    public init() {
        // Reset any leftover uploading states from previous launches
        DatabaseManager.shared.resetUploadingStatus()

        // Enable battery monitoring to adapt concurrency
        UIDevice.current.isBatteryMonitoringEnabled = true

        // Observe network changes
        NetworkMonitor.shared.$isWifi
            .combineLatest(NetworkMonitor.shared.$isCellular)
            .sink { [weak self] isWifi, isCellular in
                if isWifi || (isCellular && DatabaseManager.shared.isSyncOnCellularTailscale()) {
                    self?.triggerSync()
                } else {
                    self?.stopSync(reason: "Waiting for Wi-Fi or Tailscale connection...")
                }
            }
            .store(in: &cancellables)

        // Observe library changes
        PhotoScanner.shared.onLibraryChanged = { [weak self] in
            self?.triggerSync()
        }
    }

    public func triggerSync() {
        guard !DatabaseManager.shared.isSyncPaused() else {
            statusMessage = "Sync is paused"
            return
        }

        guard let server = DatabaseManager.shared.getPairedServer() else {
            statusMessage = "Not paired with any PC"
            return
        }

        guard syncTask == nil || syncTask?.isCancelled == true else {
            return
        }

        syncTask = Task { [weak self] in
            await self?.runSyncLoop(server: server)
        }
    }

    public func stopSync(reason: String = "Stopped") {
        syncTask?.cancel()
        syncTask = nil
        DispatchQueue.main.async { [weak self] in
            self?.isSyncing = false
            self?.uploadProgress.removeAll()
            self?.statusMessage = reason
        }
        DatabaseManager.shared.resetUploadingStatus()
    }

    private func runSyncLoop(server: PairedServer) async {
        DispatchQueue.main.async {
            self.isSyncing = true
            self.statusMessage = "Connecting to PC server..."
        }

        // 1. Resolve active IP
        guard let endpoint = await DiscoveryManager.shared.resolveServer(server: server) else {
            DispatchQueue.main.async {
                self.isSyncing = false
                self.statusMessage = "PC server unreachable"
            }
            return
        }

        // Update fallback list to put working IP first
        var ips = server.fallbackIp.components(separatedBy: ",")
        let activeStr = "\(endpoint.ip):\(endpoint.port)"
        ips.removeAll { $0 == activeStr }
        ips.insert(activeStr, at: 0)
        let newFallback = ips.joined(separator: ",")
        if newFallback != server.fallbackIp {
            DatabaseManager.shared.updatePairedServerFallbackIp(id: server.id, fallbackIp: newFallback)
        }

        // 2. Check cellular restrictions
        let isCellularTailscaleAllowed = DatabaseManager.shared.isSyncOnCellularTailscale()
        guard NetworkMonitor.shared.canSync(toHost: endpoint.ip, isCellularTailscaleAllowed: isCellularTailscaleAllowed) else {
            DispatchQueue.main.async {
                self.isSyncing = false
                self.statusMessage = "Paused — mobile sync restricted"
            }
            return
        }

        DispatchQueue.main.async {
            self.statusMessage = "Syncing with \(server.pcName)..."
        }

        // Concurrency settings
        let isCharging = UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full
        let photoLimit = isCharging ? 6 : 4
        let videoLimit = isCharging ? 3 : 2

        while !Task.isCancelled {
            guard !DatabaseManager.shared.isSyncPaused() else {
                stopSync(reason: "Sync paused")
                break
            }

            let pendingItems = DatabaseManager.shared.getPendingMediaItems(limit: 30)
            if pendingItems.isEmpty {
                DispatchQueue.main.async {
                    self.isSyncing = false
                    self.statusMessage = "All items backed up"
                }
                break
            }

            // Split photos and videos to enforce separate concurrency limits
            let photos = pendingItems.filter { $0.mediaType == .photo }
            let videos = pendingItems.filter { $0.mediaType == .video }

            await withTaskGroup(of: Void.self) { group in
                // Process photos batch
                for chunk in photos.chunked(into: photoLimit) {
                    for item in chunk {
                        group.addTask {
                            await self.uploadSingleItem(item: item, server: server, host: endpoint.ip, port: endpoint.port)
                        }
                    }
                }
                // Process videos batch
                for chunk in videos.chunked(into: videoLimit) {
                    for item in chunk {
                        group.addTask {
                            await self.uploadSingleItem(item: item, server: server, host: endpoint.ip, port: endpoint.port)
                        }
                    }
                }
            }
        }
    }

    private func uploadSingleItem(item: MediaItem, server: PairedServer, host: String, port: Int) async {
        // Mark UPLOADING in DB
        DatabaseManager.shared.updateItemStatus(mediaId: item.mediaId, status: .uploading)

        DispatchQueue.main.async {
            self.uploadProgress[item.mediaId] = 0.0
        }

        let result = await UploadPipeline.shared.uploadItem(
            item: item,
            server: server,
            host: host,
            port: port
        ) { [weak self] progress in
            DispatchQueue.main.async {
                self?.uploadProgress[item.mediaId] = progress
            }
        }

        DispatchQueue.main.async {
            self.uploadProgress.removeValue(forKey: item.mediaId)
        }

        switch result {
        case .success(let hash):
            DatabaseManager.shared.updateItemStatus(mediaId: item.mediaId, status: .done, hash: hash)
        case .failure(let error):
            print("Failed to upload \(item.fileName): \(error.localizedDescription)")
            DatabaseManager.shared.recordFailedAttempt(mediaId: item.mediaId)
        }
    }
}

private extension Array {
    func chunked(into size: Int) -> [[Element]] {
        guard size > 0 else { return [] }
        return stride(from: 0, to: count, by: size).map {
            Array(self[$0 ..< Swift.min($0 + size, count)])
        }
    }
}
