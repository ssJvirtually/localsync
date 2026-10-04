import SwiftUI

public struct PhotoTileView: View {
    public let item: MediaItem
    public let isSelected: Bool
    public let isSelectionMode: Bool
    public let uploadProgress: Double?
    public let onTap: () -> Void
    public let onLongPress: () -> Void

    @State private var thumbnail: UIImage?

    public init(
        item: MediaItem,
        isSelected: Bool,
        isSelectionMode: Bool,
        uploadProgress: Double? = nil,
        onTap: @escaping () -> Void,
        onLongPress: @escaping () -> Void
    ) {
        self.item = item
        self.isSelected = isSelected
        self.isSelectionMode = isSelectionMode
        self.uploadProgress = uploadProgress
        self.onTap = onTap
        self.onLongPress = onLongPress
    }

    public var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .bottomTrailing) {
                // 1. Thumbnail Image
                if let image = thumbnail {
                    Image(uiImage: image)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: geometry.size.width, height: geometry.size.width)
                        .clipped()
                } else {
                    Rectangle()
                        .fill(Color(UIColor.secondarySystemBackground))
                        .overlay(
                            ProgressView()
                                .scaleEffect(0.7)
                        )
                }

                // 2. Video badge (bottom-left)
                if item.mediaType == .video {
                    HStack(spacing: 3) {
                        Image(systemName: "play.fill")
                            .font(.system(size: 8))
                            .foregroundColor(.white)
                    }
                    .padding(4)
                    .background(Color.black.opacity(0.6))
                    .clipShape(Circle())
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                    .padding(4)
                }

                // 3. Status Badge (bottom-right)
                statusOverlay

                // 4. Selection Overlay (top-left)
                if isSelectionMode {
                    selectionOverlay
                }
            }
            .contentShape(Rectangle())
            .onTapGesture {
                onTap()
            }
            .onLongPressGesture {
                onLongPress()
            }
            .onAppear {
                loadThumb(size: geometry.size)
            }
        }
        .aspectRatio(1, contentMode: .fit)
    }

    @ViewBuilder
    private var statusOverlay: some View {
        if item.backupStatus == .done {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 15))
                .foregroundColor(.green)
                .background(Circle().fill(Color.black.opacity(0.5)).frame(width: 14, height: 14))
                .padding(4)
        } else if item.backupStatus == .uploading || uploadProgress != nil {
            ZStack {
                Circle()
                    .stroke(Color.white.opacity(0.3), lineWidth: 2)
                    .frame(width: 16, height: 16)
                Circle()
                    .trim(from: 0, to: CGFloat(uploadProgress ?? 0.3))
                    .stroke(Color.blue, lineWidth: 2)
                    .frame(width: 16, height: 16)
                    .rotationEffect(.degrees(-90))
            }
            .background(Circle().fill(Color.black.opacity(0.6)).frame(width: 18, height: 18))
            .padding(4)
        }
    }

    @ViewBuilder
    private var selectionOverlay: some View {
        ZStack(alignment: .topLeading) {
            if isSelected {
                Color.blue.opacity(0.3)
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 22))
                    .foregroundColor(.blue)
                    .background(Circle().fill(Color.white).frame(width: 18, height: 18))
                    .padding(6)
            } else {
                Circle()
                    .stroke(Color.white, lineWidth: 1.5)
                    .background(Circle().fill(Color.black.opacity(0.25)))
                    .frame(width: 20, height: 20)
                    .padding(6)
            }
        }
    }

    private func loadThumb(size: CGSize) {
        guard thumbnail == nil else { return }
        let targetSize = CGSize(width: size.width * 2, height: size.width * 2)
        PhotoScanner.shared.loadThumbnail(for: item.mediaId, targetSize: targetSize) { img in
            self.thumbnail = img
        }
    }
}
