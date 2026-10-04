import Foundation

public enum MediaType: String, Codable {
    case photo = "PHOTO"
    case video = "VIDEO"
}

public enum BackupStatus: String, Codable {
    case pending = "PENDING"
    case uploading = "UPLOADING"
    case done = "DONE"
    case failed = "FAILED"
}

public struct MediaItem: Identifiable, Hashable, Codable, Sendable {
    public var id: String { mediaId }
    public let mediaId: String          // PHAsset localIdentifier
    public let filePath: String         // Local asset filename or path
    public let fileName: String
    public let dateTaken: Int64         // Epoch milliseconds
    public var fileHash: String?        // SHA-256 hex string
    public let sizeBytes: Int64
    public let mediaType: MediaType
    public var backupStatus: BackupStatus
    public var lastAttemptAt: Int64?
    public let pairedServerId: Int64

    public init(
        mediaId: String,
        filePath: String = "",
        fileName: String,
        dateTaken: Int64,
        fileHash: String? = nil,
        sizeBytes: Int64,
        mediaType: MediaType,
        backupStatus: BackupStatus = .pending,
        lastAttemptAt: Int64? = nil,
        pairedServerId: Int64 = 1
    ) {
        self.mediaId = mediaId
        self.filePath = filePath
        self.fileName = fileName
        self.dateTaken = dateTaken
        self.fileHash = fileHash
        self.sizeBytes = sizeBytes
        self.mediaType = mediaType
        self.backupStatus = backupStatus
        self.lastAttemptAt = lastAttemptAt
        self.pairedServerId = pairedServerId
    }
}
