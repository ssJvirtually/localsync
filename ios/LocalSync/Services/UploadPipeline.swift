import Foundation
import CryptoKit

public final class UploadPipeline: NSObject {
    public static let shared = UploadPipeline()

    private let session: URLSession

    public override init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 600 // High resource timeout for large videos
        self.session = URLSession(configuration: config)
        super.init()
    }

    /// Uploads a single media item to the paired server.
    public func uploadItem(
        item: MediaItem,
        server: PairedServer,
        host: String,
        port: Int,
        onProgress: @escaping (Double) -> Void
    ) async -> Result<String, Error> {
        do {
            // 1. Load raw file data from PhotoKit
            let (fileData, mimeType) = try await PhotoScanner.shared.loadAssetData(for: item)

            // 2. Compute SHA-256 hash if not already cached
            let hash: String
            if let cachedHash = item.fileHash, !cachedHash.isEmpty {
                hash = cachedHash
            } else {
                let digest = SHA256.hash(data: fileData)
                hash = digest.compactMap { String(format: "%02x", $0) }.joined()
            }

            let baseUrl = "http://\(host):\(port)"

            // 3. Pre-check /exists
            if await checkFileExists(baseUrl: baseUrl, token: server.token, deviceId: server.deviceId, hash: hash) {
                print("File already exists on server: \(item.fileName). Skipping upload.")
                onProgress(1.0)
                return .success(hash)
            }

            // 4. Perform Multipart Upload
            let uploadUrl = URL(string: "\(baseUrl)/upload")!
            let boundary = "Boundary-\(UUID().uuidString)"
            var request = URLRequest(url: uploadUrl)
            request.httpMethod = "POST"
            request.setValue("Bearer \(server.token)", forHTTPHeaderField: "Authorization")
            request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

            let bodyData = createMultipartBody(
                boundary: boundary,
                fileData: fileData,
                mimeType: mimeType,
                fileName: item.fileName,
                hash: hash,
                deviceId: server.deviceId,
                mediaId: item.mediaId,
                dateTaken: item.dateTaken,
                mediaType: item.mediaType.rawValue
            )

            let delegate = ProgressDelegate(onProgress: onProgress)

            let (responseData, response) = try await session.upload(for: request, from: bodyData, delegate: delegate)
            guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
                let errorMsg = String(data: responseData, encoding: .utf8) ?? "HTTP error"
                throw NSError(domain: "UploadPipeline", code: (response as? HTTPURLResponse)?.statusCode ?? 500, userInfo: [NSLocalizedDescriptionKey: errorMsg])
            }

            onProgress(1.0)
            return .success(hash)
        } catch {
            return .failure(error)
        }
    }

    private func checkFileExists(baseUrl: String, token: String, deviceId: String, hash: String) async -> Bool {
        guard let url = URL(string: "\(baseUrl)/exists?hash=\(hash)&deviceId=\(deviceId)") else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 5

        do {
            let (data, response) = try await session.data(for: request)
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let exists = json["exists"] as? Bool {
                    return exists
                }
            }
        } catch {
            // If exists check fails, proceed with upload
        }
        return false
    }

    private func createMultipartBody(
        boundary: String,
        fileData: Data,
        mimeType: String,
        fileName: String,
        hash: String,
        deviceId: String,
        mediaId: String,
        dateTaken: Int64,
        mediaType: String
    ) -> Data {
        var body = Data()
        let lineBreak = "\r\n"

        func appendField(name: String, value: String) {
            body.append("--\(boundary)\(lineBreak)".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"\(name)\"\(lineBreak)\(lineBreak)".data(using: .utf8)!)
            body.append("\(value)\(lineBreak)".data(using: .utf8)!)
        }

        appendField(name: "hash", value: hash)
        appendField(name: "deviceId", value: deviceId)

        // Desktop expects Long for mediaId; provide numeric hash if localIdentifier is string UUID
        let numericMediaId: String
        if let _ = Int64(mediaId) {
            numericMediaId = mediaId
        } else {
            var h: Int64 = 5381
            for byte in mediaId.utf8 {
                h = ((h << 5) &+ h) &+ Int64(byte)
            }
            numericMediaId = String(abs(h))
        }
        appendField(name: "mediaId", value: numericMediaId)
        appendField(name: "fileName", value: fileName)
        appendField(name: "fileSize", value: "\(fileData.count)")
        appendField(name: "dateTaken", value: "\(dateTaken)")
        appendField(name: "mediaType", value: mediaType)
        appendField(name: "subFolder", value: "")

        // File parameter
        body.append("--\(boundary)\(lineBreak)".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(fileName)\"\(lineBreak)".data(using: .utf8)!)
        body.append("Content-Type: \(mimeType)\(lineBreak)\(lineBreak)".data(using: .utf8)!)
        body.append(fileData)
        body.append(lineBreak.data(using: .utf8)!)

        body.append("--\(boundary)--\(lineBreak)".data(using: .utf8)!)
        return body
    }
}

private final class ProgressDelegate: NSObject, URLSessionTaskDelegate {
    private let onProgress: (Double) -> Void

    init(onProgress: @escaping (Double) -> Void) {
        self.onProgress = onProgress
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard totalBytesExpectedToSend > 0 else { return }
        let progress = Double(totalBytesSent) / Double(totalBytesExpectedToSend)
        DispatchQueue.main.async { [weak self] in
            self?.onProgress(min(max(progress, 0.0), 1.0))
        }
    }
}
