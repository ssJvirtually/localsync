import SwiftUI

public struct PhotosView: View {
    @ObservedObject public var viewModel: MainViewModel
    @State private var viewerIndex: Int?
    @State private var isViewerPresented: Bool = false

    private let columns = [
        GridItem(.adaptive(minimum: 105), spacing: 2)
    ]

    public init(viewModel: MainViewModel) {
        self.viewModel = viewModel
    }

    private var groupedItems: [(dateString: String, items: [MediaItem])] {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none

        let grouped = Dictionary(grouping: viewModel.mediaItems) { item -> String in
            let date = Date(timeIntervalSince1970: TimeInterval(item.dateTaken) / 1000)
            return formatter.string(from: date)
        }

        // Sort groups by date descending
        return grouped.map { (dateString: $0.key, items: $0.value) }
            .sorted { (group1, group2) -> Bool in
                let date1 = group1.items.first?.dateTaken ?? 0
                let date2 = group2.items.first?.dateTaken ?? 0
                return date1 > date2
            }
    }

    public var body: some View {
        ZStack {
            if viewModel.mediaItems.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "photo.on.rectangle.angled")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)
                    Text("No photos found on device.")
                        .font(.body)
                        .foregroundColor(.secondary)
                    if viewModel.pairedServer != nil {
                        Button("Scan Library Now") {
                            viewModel.scanLibrary()
                        }
                        .buttonStyle(.borderedProminent)
                        .padding(.top, 8)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 14) {
                            ForEach(groupedItems, id: \.dateString) { group in
                                Section {
                                    LazyVGrid(columns: columns, spacing: 2) {
                                        ForEach(group.items) { item in
                                            PhotoTileView(
                                                item: item,
                                                isSelected: viewModel.selectedItemIds.contains(item.mediaId),
                                                isSelectionMode: viewModel.isSelectionMode,
                                                uploadProgress: viewModel.syncEngine.uploadProgress[item.mediaId],
                                                onTap: {
                                                    handleItemTap(item)
                                                },
                                                onLongPress: {
                                                    handleItemLongPress(item)
                                                }
                                            )
                                        }
                                    }
                                } header: {
                                    Text(group.dateString)
                                        .font(.subheadline)
                                        .fontWeight(.bold)
                                        .foregroundColor(.primary)
                                        .padding(.horizontal, 8)
                                        .padding(.top, 8)
                                }
                            }
                        }
                        .padding(.horizontal, 2)
                    }
                }
            }
        }
        .fullScreenCover(isPresented: $isViewerPresented) {
            if let index = viewerIndex {
                MediaViewerModal(
                    items: viewModel.mediaItems,
                    currentIndex: Binding(
                        get: { index },
                        set: { viewerIndex = $0 }
                    ),
                    onDismiss: {
                        isViewerPresented = false
                    }
                )
            }
        }
    }

    private func handleItemTap(_ item: MediaItem) {
        if viewModel.isSelectionMode {
            let generator = UIImpactFeedbackGenerator(style: .light)
            generator.impactOccurred()
            viewModel.toggleItemSelection(item)
        } else {
            if let index = viewModel.mediaItems.firstIndex(where: { $0.mediaId == item.mediaId }) {
                viewerIndex = index
                isViewerPresented = true
            }
        }
    }

    private func handleItemLongPress(_ item: MediaItem) {
        let generator = UIImpactFeedbackGenerator(style: .medium)
        generator.impactOccurred()
        if !viewModel.isSelectionMode {
            viewModel.isSelectionMode = true
        }
        viewModel.toggleItemSelection(item)
    }
}
