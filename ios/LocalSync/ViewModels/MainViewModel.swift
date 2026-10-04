import Foundation
import SwiftUI
import Combine
import UIKit

@MainActor
public final class MainViewModel: ObservableObject {
    @Published public var pairedServer: PairedServer?
    @Published public var mediaItems: [MediaItem] = []
    @Published public var totalCount: Int = 0
    @Published public var backedUpCount: Int = 0
    @Published public var isSyncPaused: Bool = false
    @Published public var isSyncOnCellularTailscale: Bool = false

    // Contextual selection state
    @Published public var selectedItemIds: Set<String> = []
    @Published public var isSelectionMode: Bool = false

    // Sync Engine hooks
    @ObservedObject public var syncEngine = SyncEngine.shared
    @ObservedObject public var networkMonitor = NetworkMonitor.shared

    private var cancellables = Set<AnyCancellable>()

    public init() {
        refreshState()

        // Auto reload when PhotoScanner detects new media
        PhotoScanner.shared.onLibraryChanged = { [weak self] in
            Task { @MainActor in
                self?.refreshState()
            }
        }

        // Periodically refresh items & counts to reflect upload changes
        Timer.publish(every: 1.5, on: .main, in: .common)
            .autoconnect()
            .sink { [weak self] _ in
                self?.refreshCountsAndStatus()
            }
            .store(in: &cancellables)
    }

    public func refreshState() {
        self.pairedServer = DatabaseManager.shared.getPairedServer()
        self.isSyncPaused = DatabaseManager.shared.isSyncPaused()
        self.isSyncOnCellularTailscale = DatabaseManager.shared.isSyncOnCellularTailscale()
        self.mediaItems = DatabaseManager.shared.getAllMediaItems()

        let counts = DatabaseManager.shared.getCounts()
        self.totalCount = counts.total
        self.backedUpCount = counts.backedUp

        if pairedServer != nil && !isSyncPaused {
            SyncEngine.shared.triggerSync()
        }
    }

    private func refreshCountsAndStatus() {
        let counts = DatabaseManager.shared.getCounts()
        self.totalCount = counts.total
        self.backedUpCount = counts.backedUp

        // Refresh list if count changed or uploads in flight
        if !syncEngine.uploadProgress.isEmpty {
            self.mediaItems = DatabaseManager.shared.getAllMediaItems()
        }
    }

    public func scanLibrary() {
        guard let server = pairedServer else { return }
        Task {
            _ = await PhotoScanner.shared.scan(pairedServerId: server.id)
            self.refreshState()
            SyncEngine.shared.triggerSync()
        }
    }

    public func toggleSyncPause(_ paused: Bool) {
        DatabaseManager.shared.setSyncPaused(paused)
        self.isSyncPaused = paused
        if paused {
            SyncEngine.shared.stopSync(reason: "Sync paused by user")
        } else {
            SyncEngine.shared.triggerSync()
        }
    }

    public func toggleSyncOnCellularTailscale(_ enabled: Bool) {
        DatabaseManager.shared.setSyncOnCellularTailscale(enabled)
        self.isSyncOnCellularTailscale = enabled
        SyncEngine.shared.triggerSync()
    }

    public func unpair() {
        SyncEngine.shared.stopSync(reason: "Unpaired from PC")
        DatabaseManager.shared.unpair()
        self.selectedItemIds.removeAll()
        self.isSelectionMode = false
        refreshState()
    }

    public func pairWithPayload(_ payload: QrPayload) async -> Result<String, Error> {
        let deviceName = UIDevice.current.name
        let result = await DiscoveryManager.shared.verifyPairing(payload: payload, deviceName: deviceName)

        switch result {
        case .success(let verified):
            var candidateIps: [String] = []
            candidateIps.append("\(verified.ip):\(verified.port)")
            if let ips = payload.ips {
                for ip in ips {
                    let entry = "\(ip):\(payload.port)"
                    if !candidateIps.contains(entry) {
                        candidateIps.append(entry)
                    }
                }
            }

            let fallbackString = candidateIps.joined(separator: ",")
            let server = PairedServer(
                serviceName: payload.service,
                pcName: payload.pcName,
                token: payload.token,
                deviceId: verified.deviceId,
                fallbackIp: fallbackString,
                pairedAt: Int64(Date().timeIntervalSince1970 * 1000)
            )

            let serverId = DatabaseManager.shared.insertPairedServer(server)
            _ = await PhotoScanner.shared.scan(pairedServerId: serverId)

            refreshState()
            SyncEngine.shared.triggerSync()
            return .success(payload.pcName)

        case .failure(let error):
            return .failure(error)
        }
    }

    public func pairManually(ip: String, port: Int, token: String, pcName: String) async -> Result<String, Error> {
        let cleanIp = ip.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = pcName.isEmpty ? "Manual PC" : pcName
        let payload = QrPayload(
            service: "_photobackup._tcp.local.",
            token: token.trimmingCharacters(in: .whitespacesAndNewlines),
            pcName: name,
            port: port,
            localIp: cleanIp,
            ips: [cleanIp]
        )
        return await pairWithPayload(payload)
    }

    // MARK: - Selection & Bulk Operations

    public func toggleItemSelection(_ item: MediaItem) {
        if selectedItemIds.contains(item.mediaId) {
            selectedItemIds.remove(item.mediaId)
            if selectedItemIds.isEmpty {
                isSelectionMode = false
            }
        } else {
            selectedItemIds.insert(item.mediaId)
        }
    }

    public func clearSelection() {
        selectedItemIds.removeAll()
        isSelectionMode = false
    }

    public func deleteSelectedItems() async {
        let idsToDelete = Array(selectedItemIds)
        clearSelection()

        // 1. Delete from Photo Library (requests system confirmation)
        try? await PhotoScanner.shared.deleteAssets(localIdentifiers: idsToDelete)

        // 2. Delete from SQLite
        DatabaseManager.shared.deleteMediaItems(ids: idsToDelete)
        refreshState()
    }
}
