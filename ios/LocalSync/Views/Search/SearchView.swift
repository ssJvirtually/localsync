import SwiftUI

public struct SearchView: View {
    @ObservedObject public var viewModel: MainViewModel
    @State private var searchQuery: String = ""
    @State private var viewerIndex: Int?
    @State private var isViewerPresented: Bool = false

    private let columns = [
        GridItem(.adaptive(minimum: 105), spacing: 2)
    ]

    public init(viewModel: MainViewModel) {
        self.viewModel = viewModel
    }

    private var filteredItems: [MediaItem] {
        let query = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return [] }
        return viewModel.mediaItems.filter {
            $0.fileName.localizedCaseInsensitiveContains(query)
        }
    }

    public var body: some View {
        VStack(spacing: 0) {
            // Search Input Bar
            HStack {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.secondary)
                TextField("Search photos by filename...", text: $searchQuery)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                if !searchQuery.isEmpty {
                    Button(action: { searchQuery = "" }) {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.secondary)
                    }
                }
            }
            .padding(10)
            .background(Color(UIColor.secondarySystemBackground))
            .cornerRadius(12)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            // Content Area
            if searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "text.magnifyingglass")
                        .font(.system(size: 40))
                        .foregroundColor(.secondary.opacity(0.5))
                    Text("Type to search your backed up files.")
                        .font(.body)
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if filteredItems.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "questionmark.folder")
                        .font(.system(size: 40))
                        .foregroundColor(.secondary.opacity(0.5))
                    Text("No photos matching \"\(searchQuery)\" found.")
                        .font(.body)
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Found \(filteredItems.count) results")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .padding(.horizontal, 16)
                        .padding(.top, 4)

                    ScrollView {
                        LazyVGrid(columns: columns, spacing: 2) {
                            ForEach(filteredItems) { item in
                                PhotoTileView(
                                    item: item,
                                    isSelected: viewModel.selectedItemIds.contains(item.mediaId),
                                    isSelectionMode: viewModel.isSelectionMode,
                                    uploadProgress: viewModel.syncEngine.uploadProgress[item.mediaId],
                                    onTap: {
                                        if viewModel.isSelectionMode {
                                            viewModel.toggleItemSelection(item)
                                        } else {
                                            if let index = filteredItems.firstIndex(where: { $0.mediaId == item.mediaId }) {
                                                viewerIndex = index
                                                isViewerPresented = true
                                            }
                                        }
                                    },
                                    onLongPress: {
                                        if !viewModel.isSelectionMode {
                                            viewModel.isSelectionMode = true
                                        }
                                        viewModel.toggleItemSelection(item)
                                    }
                                )
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
                    items: filteredItems,
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
}
