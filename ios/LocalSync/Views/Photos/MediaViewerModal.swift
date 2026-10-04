import SwiftUI
import Photos
import AVKit

public struct MediaViewerModal: View {
    public let items: [MediaItem]
    @Binding public var currentIndex: Int
    public let onDismiss: () -> Void

    public init(items: [MediaItem], currentIndex: Binding<Int>, onDismiss: @escaping () -> Void) {
        self.items = items
        self._currentIndex = currentIndex
        self.onDismiss = onDismiss
    }

    public var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if !items.isEmpty && currentIndex >= 0 && currentIndex < items.count {
                TabView(selection: $currentIndex) {
                    ForEach(Array(items.enumerated()), id: \.element.mediaId) { index, item in
                        SingleMediaView(item: item)
                            .tag(index)
                    }
                }
                .tabViewStyle(PageTabViewStyle(indexDisplayMode: .never))

                // Top Bar
                VStack {
                    HStack {
                        Button(action: onDismiss) {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 20, weight: .semibold))
                                .foregroundColor(.white)
                                .padding(10)
                                .background(Circle().fill(Color.black.opacity(0.5)))
                        }

                        Spacer()

                        if let currentItem = items[safe: currentIndex] {
                            VStack(alignment: .trailing, spacing: 2) {
                                Text(currentItem.fileName)
                                    .font(.subheadline)
                                    .fontWeight(.medium)
                                    .foregroundColor(.white)
                                    .lineLimit(1)

                                Text(formatDate(currentItem.dateTaken))
                                    .font(.caption2)
                                    .foregroundColor(.white.opacity(0.7))
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 50)

                    Spacer()

                    // Bottom Bar Info
                    if let currentItem = items[safe: currentIndex] {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(humanReadableSize(currentItem.sizeBytes))
                                    .font(.caption)
                                    .foregroundColor(.white.opacity(0.8))

                                HStack(spacing: 4) {
                                    Circle()
                                        .fill(currentItem.backupStatus == .done ? Color.green : Color.orange)
                                        .frame(width: 6, height: 6)
                                    Text(currentItem.backupStatus == .done ? "Backed up to PC" : "Pending backup")
                                        .font(.caption2)
                                        .foregroundColor(.white.opacity(0.8))
                                }
                            }

                            Spacer()

                            Button(action: {
                                shareItem(currentItem)
                            }) {
                                Image(systemName: "square.and.arrow.up")
                                    .font(.system(size: 18))
                                    .foregroundColor(.white)
                                    .padding(8)
                                    .background(Circle().fill(Color.black.opacity(0.5)))
                            }
                        }
                        .padding(.horizontal, 20)
                        .padding(.vertical, 14)
                        .background(Color.black.opacity(0.6))
                    }
                }
            }
        }
    }

    private func formatDate(_ epochMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000)
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    private func humanReadableSize(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }

    private func shareItem(_ item: MediaItem) {
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: [item.mediaId], options: nil)
        guard let asset = assets.firstObject else { return }

        let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .highQualityFormat

        PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, _, _, _ in
            guard let data = data else { return }
            DispatchQueue.main.async {
                let activityVC = UIActivityViewController(activityItems: [data], applicationActivities: nil)
                if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                   let root = scene.windows.first?.rootViewController {
                    root.present(activityVC, animated: true)
                }
            }
        }
    }
}

private struct SingleMediaView: View {
    let item: MediaItem
    @State private var fullImage: UIImage?
    @State private var avPlayer: AVPlayer?

    var body: some View {
        ZStack {
            if item.mediaType == .video {
                if let player = avPlayer {
                    VideoPlayer(player: player)
                        .onAppear {
                            player.play()
                        }
                        .onDisappear {
                            player.pause()
                        }
                } else {
                    ProgressView()
                        .tint(.white)
                }
            } else {
                if let image = fullImage {
                    ZoomableScrollView {
                        Image(uiImage: image)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                    }
                } else {
                    ProgressView()
                        .tint(.white)
                }
            }
        }
        .onAppear {
            loadMedia()
        }
    }

    private func loadMedia() {
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: [item.mediaId], options: nil)
        guard let asset = assets.firstObject else { return }

        if item.mediaType == .video {
            let options = PHVideoRequestOptions()
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .highQualityFormat
            PHImageManager.default().requestPlayerItem(forVideo: asset, options: options) { playerItem, _ in
                if let playerItem = playerItem {
                    DispatchQueue.main.async {
                        self.avPlayer = AVPlayer(playerItem: playerItem)
                    }
                }
            }
        } else {
            let options = PHImageRequestOptions()
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .highQualityFormat
            let targetSize = CGSize(width: UIScreen.main.bounds.width * 2, height: UIScreen.main.bounds.height * 2)
            PHImageManager.default().requestImage(for: asset, targetSize: targetSize, contentMode: .aspectFit, options: options) { img, _ in
                DispatchQueue.main.async {
                    self.fullImage = img
                }
            }
        }
    }
}

private struct ZoomableScrollView<Content: View>: UIViewRepresentable {
    private var content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    func makeUIView(context: Context) -> UIScrollView {
        let scrollView = UIScrollView()
        scrollView.delegate = context.coordinator
        scrollView.maximumZoomScale = 4.0
        scrollView.minimumZoomScale = 1.0
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false

        let host = UIHostingController(rootView: content)
        host.view.backgroundColor = .clear
        host.view.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(host.view)

        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: scrollView.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: scrollView.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: scrollView.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: scrollView.bottomAnchor),
            host.view.widthAnchor.constraint(equalTo: scrollView.widthAnchor),
            host.view.heightAnchor.constraint(equalTo: scrollView.heightAnchor)
        ])

        return scrollView
    }

    func updateUIView(_ uiView: UIScrollView, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    class Coordinator: NSObject, UIScrollViewDelegate {
        func viewForZooming(in scrollView: UIScrollView) -> UIView? {
            return scrollView.subviews.first
        }
    }
}

private extension Collection {
    subscript(safe index: Index) -> Element? {
        return indices.contains(index) ? self[index] : nil
    }
}
