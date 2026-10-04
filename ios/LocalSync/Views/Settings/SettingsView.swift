import SwiftUI

public struct SettingsView: View {
    @ObservedObject public var viewModel: MainViewModel
    @Binding public var showPairingScanner: Bool

    @State private var showUnpairConfirmation: Bool = false

    public init(viewModel: MainViewModel, showPairingScanner: Binding<Bool>) {
        self.viewModel = viewModel
        self._showPairingScanner = showPairingScanner
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Header
                HStack(spacing: 14) {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 22))
                        .foregroundColor(.blue)
                        .padding(10)
                        .background(Color.blue.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 12))

                    VStack(alignment: .leading, spacing: 2) {
                        Text("Settings")
                            .font(.title2)
                            .fontWeight(.bold)
                        Text("Manage backup preferences & devices")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                }
                .padding(.top, 8)

                if let server = viewModel.pairedServer {
                    // 1. Status Dashboard Card
                    statusCard

                    // 2. Paired PC Details Card
                    pairedPcCard(server: server)
                } else {
                    // Unpaired Hero Card
                    unpairedHeroCard
                }

                // 3. Sync Preferences Card
                syncPreferencesCard

                // 4. Danger Zone Card
                if viewModel.pairedServer != nil {
                    dangerZoneCard
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .confirmationDialog(
            "Are you sure you want to unpair from this PC?",
            isPresented: $showUnpairConfirmation,
            titleVisibility: .visible
        ) {
            Button("Unpair from PC", role: .destructive) {
                viewModel.unpair()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Synced status information will be cleared from this iPhone. Files already uploaded to your PC will remain safe.")
        }
    }

    // MARK: - Subcards

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Backup Status")
                    .font(.headline)
                    .fontWeight(.semibold)

                Spacer()

                HStack(spacing: 6) {
                    Circle()
                        .fill(statusColor)
                        .frame(width: 8, height: 8)
                    Text(statusText)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(statusColor)
                }
            }

            let progress = viewModel.totalCount > 0 ? Double(viewModel.backedUpCount) / Double(viewModel.totalCount) : 0.0

            ProgressView(value: progress)
                .tint(.blue)
                .scaleEffect(x: 1, y: 1.5, anchor: .center)

            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(viewModel.backedUpCount) of \(viewModel.totalCount) files backed up")
                        .font(.subheadline)
                        .foregroundColor(.secondary)

                    if viewModel.totalCount > 0 && viewModel.backedUpCount < viewModel.totalCount {
                        let remaining = viewModel.totalCount - viewModel.backedUpCount
                        Text("\(remaining) items remaining")
                            .font(.caption)
                            .foregroundColor(.secondary.opacity(0.8))
                    }
                }

                Spacer()

                Text("\(Int(progress * 100))%")
                    .font(.title2)
                    .fontWeight(.bold)
                    .foregroundColor(.blue)
            }
        }
        .padding(18)
        .background(Color.blue.opacity(0.08))
        .cornerRadius(18)
    }

    private var pairedPcCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Paired PC Details")
                .font(.headline)
                .fontWeight(.semibold)

            if let server = viewModel.pairedServer {
                VStack(alignment: .leading, spacing: 10) {
                    detailRow(title: "PC Name", value: server.pcName)
                    detailRow(title: "Server IPs (First is active)", value: server.fallbackIp.replacingOccurrences(of: ",", with: "\n"))
                    detailRow(title: "Pairing Date", value: formatPairDate(server.pairedAt))
                    detailRow(title: "Device ID", value: server.deviceId)
                }
            }
        }
        .padding(18)
        .background(Color(UIColor.secondarySystemBackground))
        .cornerRadius(18)
    }

    private var unpairedHeroCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Backup Offline & Locally")
                .font(.headline)
                .fontWeight(.bold)
                .foregroundColor(.blue)

            Text("Automatically back up your photos and videos to your computer over Wi-Fi. Safe, private, and with zero subscriptions.")
                .font(.subheadline)
                .foregroundColor(.secondary)

            Button(action: {
                showPairingScanner = true
            }) {
                HStack {
                    Image(systemName: "qrcode.viewfinder")
                    Text("Pair with your PC")
                        .fontWeight(.bold)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .cornerRadius(12)
        }
        .padding(20)
        .background(Color.blue.opacity(0.08))
        .cornerRadius(18)
    }

    private var syncPreferencesCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Sync Preferences")
                .font(.headline)
                .fontWeight(.semibold)

            // Pause toggle
            Toggle(isOn: Binding(
                get: { viewModel.isSyncPaused },
                set: { viewModel.toggleSyncPause($0) }
            )) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Pause Background Sync")
                        .font(.body)
                        .fontWeight(.medium)
                    Text("Temporarily halt auto photo uploads")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .disabled(viewModel.pairedServer == nil)

            Divider()

            // Cellular Tailscale toggle
            Toggle(isOn: Binding(
                get: { viewModel.isSyncOnCellularTailscale },
                set: { viewModel.toggleSyncOnCellularTailscale($0) }
            )) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Sync on Mobile via Tailscale")
                        .font(.body)
                        .fontWeight(.medium)
                    Text("Allow uploads on mobile network only when connected to Tailscale IP")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            Divider()

            // Backup Now
            Button(action: {
                viewModel.scanLibrary()
            }) {
                HStack {
                    Image(systemName: "arrow.clockwise")
                    Text("Backup Now")
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
            }
            .buttonStyle(.bordered)
            .disabled(viewModel.pairedServer == nil)
        }
        .padding(18)
        .background(Color(UIColor.secondarySystemBackground))
        .cornerRadius(18)
    }

    private var dangerZoneCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Danger Zone")
                .font(.headline)
                .fontWeight(.semibold)
                .foregroundColor(.red)

            Text("Unpairing will delete all synced status information from this phone. Previously uploaded files on your PC will remain safe.")
                .font(.caption)
                .foregroundColor(.secondary)

            Button(role: .destructive, action: {
                showUnpairConfirmation = true
            }) {
                HStack {
                    Image(systemName: "link.badge.plus")
                    Text("Unpair from PC")
                        .fontWeight(.bold)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
            .cornerRadius(12)
        }
        .padding(18)
        .background(Color.red.opacity(0.08))
        .cornerRadius(18)
    }

    private func detailRow(title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption2)
                .foregroundColor(.secondary)
            Text(value)
                .font(.subheadline)
                .fontWeight(.medium)
        }
    }

    private var statusColor: Color {
        if viewModel.isSyncPaused {
            return .orange
        } else if viewModel.backedUpCount == viewModel.totalCount && viewModel.totalCount > 0 {
            return .green
        } else {
            return .blue
        }
    }

    private var statusText: String {
        if viewModel.isSyncPaused {
            return "Paused"
        } else if viewModel.backedUpCount == viewModel.totalCount && viewModel.totalCount > 0 {
            return "Fully Synced"
        } else if viewModel.syncEngine.isSyncing {
            return "Syncing..."
        } else {
            return "Idle"
        }
    }

    private func formatPairDate(_ epochMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000)
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}
