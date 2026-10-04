import SwiftUI
import Photos

public struct MainTabView: View {
    @StateObject private var viewModel = MainViewModel()
    @State private var selectedTab: Int = 0
    @State private var showPairingScanner: Bool = false
    @State private var showDeleteConfirmation: Bool = false

    public init() {}

    public var body: some View {
        ZStack(alignment: .top) {
            TabView(selection: $selectedTab) {
                NavigationView {
                    PhotosView(viewModel: viewModel)
                        .navigationTitle("Photos")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            if !viewModel.isSelectionMode {
                                ToolbarItem(placement: .navigationBarTrailing) {
                                    if viewModel.pairedServer == nil {
                                        Button(action: { showPairingScanner = true }) {
                                            Image(systemName: "qrcode.viewfinder")
                                        }
                                    }
                                }
                            }
                        }
                }
                .navigationViewStyle(.stack)
                .tabItem {
                    Label("Photos", systemImage: "photo.on.rectangle.angled")
                }
                .tag(0)

                NavigationView {
                    SearchView(viewModel: viewModel)
                        .navigationTitle("Search")
                        .navigationBarTitleDisplayMode(.inline)
                }
                .navigationViewStyle(.stack)
                .tabItem {
                    Label("Search", systemImage: "magnifyingglass")
                }
                .tag(1)

                NavigationView {
                    SettingsView(viewModel: viewModel, showPairingScanner: $showPairingScanner)
                        .navigationTitle("Settings")
                        .navigationBarTitleDisplayMode(.inline)
                }
                .navigationViewStyle(.stack)
                .tabItem {
                    Label("Settings", systemImage: "gearshape")
                }
                .tag(2)
            }

            // Contextual Top Action Bar when in selection mode
            if viewModel.isSelectionMode {
                VStack(spacing: 0) {
                    HStack {
                        Button(action: {
                            viewModel.clearSelection()
                        }) {
                            Image(systemName: "xmark")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(.primary)
                        }

                        Text("\(viewModel.selectedItemIds.count) selected")
                            .font(.headline)
                            .fontWeight(.bold)
                            .padding(.leading, 8)

                        Spacer()

                        Button(action: shareSelectedMedia) {
                            Image(systemName: "square.and.arrow.up")
                                .font(.system(size: 18))
                                .foregroundColor(.primary)
                                .padding(.horizontal, 8)
                        }

                        Button(action: {
                            showDeleteConfirmation = true
                        }) {
                            Image(systemName: "trash")
                                .font(.system(size: 18))
                                .foregroundColor(.red)
                                .padding(.horizontal, 4)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 50)
                    .padding(.bottom, 12)
                    .background(Color(UIColor.systemBackground).shadow(radius: 2))

                    Spacer()
                }
                .transition(.move(edge: .top))
                .animation(.easeInOut(duration: 0.2), value: viewModel.isSelectionMode)
                .ignoresSafeArea(edges: .top)
            }
        }
        .fullScreenCover(isPresented: $showPairingScanner) {
            CameraScannerView(viewModel: viewModel)
        }
        .confirmationDialog(
            "Delete from device?",
            isPresented: $showDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button("Delete from Device", role: .destructive) {
                Task {
                    await viewModel.deleteSelectedItems()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            let allDone = viewModel.selectedItemIds.allSatisfy { id in
                viewModel.mediaItems.first(where: { $0.mediaId == id })?.backupStatus == .done
            }
            if allDone {
                Text("These items can be deleted safely from your device as they are already backed up to your PC.")
            } else {
                Text("Warning: Some selected items have not completed backup yet. Are you sure you want to delete them from your device?")
            }
        }
        .onAppear {
            if viewModel.pairedServer != nil {
                viewModel.scanLibrary()
            }
        }
    }

    private func shareSelectedMedia() {
        let selectedIds = Array(viewModel.selectedItemIds)
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: selectedIds, options: nil)
        var itemsToShare: [Any] = []

        let group = DispatchGroup()
        assets.enumerateObjects { asset, _, _ in
            group.enter()
            let options = PHImageRequestOptions()
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .highQualityFormat
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, _, _, _ in
                if let data = data {
                    itemsToShare.append(data)
                }
                group.leave()
            }
        }

        group.notify(queue: .main) {
            guard !itemsToShare.isEmpty else { return }
            let activityVC = UIActivityViewController(activityItems: itemsToShare, applicationActivities: nil)
            if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
               let root = scene.windows.first?.rootViewController {
                root.present(activityVC, animated: true)
            }
        }
    }
}
