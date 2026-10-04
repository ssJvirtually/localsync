import Foundation
import Photos
import UIKit

public final class PhotoScanner: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {
    public static let shared = PhotoScanner()

    private let imageManager = PHCachingImageManager()
    private var allPhotosFetchResult: PHFetchResult<PHAsset>?

    public var onLibraryChanged: (() -> Void)?

    public override init() {
        super.init()
        PHPhotoLibrary.shared().register(self)
    }

    deinit {
        PHPhotoLibrary.shared().unregisterChangeObserver(self)
    }

    // MARK: - Permission

    public func requestAuthorization() async -> PHAuthorizationStatus {
        if #available(iOS 14, *) {
            return await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        } else {
            return await withCheckedContinuation { continuation in
                PHPhotoLibrary.requestAuthorization { status in
                    continuation.resume(returning: status)
                }
            }
        }
    }

    public func hasAuthorization() -> Bool {
        if #available(iOS 14, *) {
            let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            return status == .authorized || status == .limited
        } else {
            return PHPhotoLibrary.authorizationStatus() == .authorized
        }
    }

    // MARK: - Scanning

    public func scan(pairedServerId: Int64) async -> [MediaItem] {
        if !hasAuthorization() {
            let status = await requestAuthorization()
            guard status == .authorized || status == .limited else {
                return []
            }
        }

        return await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                guard let self = self else {
                    continuation.resume(returning: [])
                    return
                }

                let fetchOptions = PHFetchOptions()
                fetchOptions.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: true)]
                fetchOptions.predicate = NSPredicate(format: "mediaType == %d OR mediaType == %d", PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue)

                let fetchResult = PHAsset.fetchAssets(with: fetchOptions)
                self.allPhotosFetchResult = fetchResult

                var mediaItems: [MediaItem] = []
                mediaItems.reserveCapacity(fetchResult.count)

                fetchResult.enumerateObjects { asset, _, _ in
                    if let item = self.createMediaItem(from: asset, pairedServerId: pairedServerId) {
                        mediaItems.append(item)
                    }
                }

                // Batch insert into SQLite
                DatabaseManager.shared.insertOrIgnoreMediaItems(mediaItems)
                continuation.resume(returning: mediaItems)
            }
        }
    }

    private func createMediaItem(from asset: PHAsset, pairedServerId: Int64) -> MediaItem? {
        let mediaType: MediaType = asset.mediaType == .video ? .video : .photo
        let dateTaken = Int64((asset.creationDate ?? Date()).timeIntervalSince1970 * 1000)

        var fileName = ""
        var fileSize: Int64 = 0

        let resources = PHAssetResource.assetResources(for: asset)
        if let primaryResource = resources.first(where: { $0.type == (asset.mediaType == .video ? .video : .photo) }) ?? resources.first {
            fileName = primaryResource.originalFilename
            if let size = primaryResource.value(forKey: "fileSize") as? Int64, size > 0 {
                fileSize = size
            }
        }

        if fileName.isEmpty {
            let ext = mediaType == .video ? "mp4" : "jpg"
            fileName = "IMG_\(asset.localIdentifier.replacingOccurrences(of: "/", with: "_")).\(ext)"
        }

        // Estimate size if missing
        if fileSize <= 0 {
            fileSize = mediaType == .video ? 10 * 1024 * 1024 : 3 * 1024 * 1024
        }

        return MediaItem(
            mediaId: asset.localIdentifier,
            filePath: asset.localIdentifier,
            fileName: fileName,
            dateTaken: dateTaken,
            fileHash: nil,
            sizeBytes: fileSize,
            mediaType: mediaType,
            backupStatus: .pending,
            lastAttemptAt: nil,
            pairedServerId: pairedServerId
        )
    }

    // MARK: - Asset Data Loading for Upload

    public func loadAssetData(for item: MediaItem) async throws -> (data: Data, mimeType: String) {
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: [item.mediaId], options: nil)
        guard let asset = assets.firstObject else {
            throw NSError(domain: "PhotoScanner", code: 404, userInfo: [NSLocalizedDescriptionKey: "Asset not found: \(item.mediaId)"])
        }

        if item.mediaType == .video {
            return try await loadVideoData(asset: asset)
        } else {
            return try await loadImageData(asset: asset)
        }
    }

    private func loadImageData(asset: PHAsset) async throws -> (data: Data, mimeType: String) {
        let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = true
        options.version = .current
        options.deliveryMode = .highQualityFormat

        return try await withCheckedThrowingContinuation { continuation in
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, uti, _, info in
                if let error = info?[PHImageErrorKey] as? Error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let data = data else {
                    continuation.resume(throwing: NSError(domain: "PhotoScanner", code: 500, userInfo: [NSLocalizedDescriptionKey: "Failed to read image data"]))
                    return
                }
                let mimeType = (uti as String?)?.contains("png") == true ? "image/png" : "image/jpeg"
                continuation.resume(returning: (data, mimeType))
            }
        }
    }

    private func loadVideoData(asset: PHAsset) async throws -> (data: Data, mimeType: String) {
        let options = PHVideoRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .highQualityFormat

        return try await withCheckedThrowingContinuation { continuation in
            PHImageManager.default().requestAVAsset(forVideo: asset, options: options) { avAsset, _, info in
                if let error = info?[PHImageErrorKey] as? Error {
                    continuation.resume(throwing: error)
                    return
                }

                if let urlAsset = avAsset as? AVURLAsset {
                    do {
                        let data = try Data(contentsOf: urlAsset.url)
                        continuation.resume(returning: (data, "video/mp4"))
                    } catch {
                        continuation.resume(throwing: error)
                    }
                } else {
                    continuation.resume(throwing: NSError(domain: "PhotoScanner", code: 500, userInfo: [NSLocalizedDescriptionKey: "Could not export AVAsset to data"]))
                }
            }
        }
    }

    // MARK: - Thumbnails

    public func loadThumbnail(for assetId: String, targetSize: CGSize, completion: @escaping (UIImage?) -> Void) {
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: [assetId], options: nil)
        guard let asset = assets.firstObject else {
            completion(nil)
            return
        }

        let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = true
        options.resizeMode = .fast
        options.deliveryMode = .opportunistic

        imageManager.requestImage(for: asset, targetSize: targetSize, contentMode: .aspectFill, options: options) { image, _ in
            completion(image)
        }
    }

    // MARK: - Delete Assets from Photo Library

    public func deleteAssets(localIdentifiers: [String]) async throws {
        guard !localIdentifiers.isEmpty else { return }
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: localIdentifiers, options: nil)
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.deleteAssets(assets)
        }
    }

    // MARK: - PHPhotoLibraryChangeObserver

    public func photoLibraryDidChange(_ changeInstance: PHChange) {
        guard let fetchResult = allPhotosFetchResult,
              let changeDetails = changeInstance.changeDetails(for: fetchResult) else {
            return
        }

        self.allPhotosFetchResult = changeDetails.fetchResultAfterChanges

        let insertedObjects = changeDetails.insertedObjects
        if !insertedObjects.isEmpty {
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                guard let self = self else { return }
                guard let server = DatabaseManager.shared.getPairedServer() else { return }

                var newItems: [MediaItem] = []
                for asset in insertedObjects {
                    if let item = self.createMediaItem(from: asset, pairedServerId: server.id) {
                        newItems.append(item)
                    }
                }
                DatabaseManager.shared.insertOrIgnoreMediaItems(newItems)

                DispatchQueue.main.async {
                    self.onLibraryChanged?()
                }
            }
        }
    }
}
