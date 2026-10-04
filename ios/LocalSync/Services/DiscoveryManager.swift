import Foundation
import Network

public final class DiscoveryManager: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    public static let shared = DiscoveryManager()

    private var netServiceBrowser: NetServiceBrowser?
    private var resolvingServices: [NetService] = []
    private var discoveredEndpoints: [(ip: String, port: Int, name: String)] = []

    private let session: URLSession

    public override init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 4
        config.timeoutIntervalForResource = 5
        self.session = URLSession(configuration: config)
        super.init()
    }

    /// Resolves the reachable IP and port for the given paired server.
    /// Tries mDNS first, followed by sequential ping to cached fallback IPs (/health).
    public func resolveServer(server: PairedServer) async -> (ip: String, port: Int)? {
        // 1. Check fallback IPs
        let fallbacks = server.fallbackIp.components(separatedBy: ",")
        for candidate in fallbacks {
            let trimmed = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }

            let parts = trimmed.components(separatedBy: ":")
            let host = parts[0]
            let port = parts.count > 1 ? (Int(parts[1]) ?? 8080) : 8080

            if await checkHealth(host: host, port: port) {
                // Return immediately if working
                return (host, port)
            }
        }

        // 2. Try mDNS resolution as backup
        if let resolved = await resolveViaMdns(serviceName: server.pcName) {
            if await checkHealth(host: resolved.ip, port: resolved.port) {
                return (resolved.ip, resolved.port)
            }
        }

        return nil
    }

    /// Verifies and executes pairing with the PC server using the scanned QR payload.
    public func verifyPairing(payload: QrPayload, deviceName: String) async -> Result<(ip: String, port: Int, deviceId: String), Error> {
        var candidateIps: [String] = []
        if let ips = payload.ips {
            candidateIps.append(contentsOf: ips)
        }
        if !candidateIps.contains(payload.localIp) {
            candidateIps.append(payload.localIp)
        }

        // Try candidate IPs
        for ip in candidateIps {
            let port = payload.port
            do {
                if let deviceId = try await executeVerifyRequest(host: ip, port: port, token: payload.token, deviceName: deviceName) {
                    return .success((ip, port, deviceId))
                }
            } catch {
                print("Failed to verify on \(ip):\(port): \(error.localizedDescription)")
            }
        }

        // If direct candidate IPs failed, try mDNS
        if let resolved = await resolveViaMdns(serviceName: payload.pcName) {
            do {
                if let deviceId = try await executeVerifyRequest(host: resolved.ip, port: resolved.port, token: payload.token, deviceName: deviceName) {
                    return .success((resolved.ip, resolved.port, deviceId))
                }
            } catch {
                print("Failed to verify via mDNS: \(error.localizedDescription)")
            }
        }

        let error = NSError(
            domain: "LocalSync",
            code: 404,
            userInfo: [NSLocalizedDescriptionKey: "Server is unreachable on all network interfaces. Check if PC and phone are on the same Wi-Fi or connected to Tailscale."]
        )
        return .failure(error)
    }

    // MARK: - Health Check

    public func checkHealth(host: String, port: Int) async -> Bool {
        guard let url = URL(string: "http://\(host):\(port)/health") else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 3

        do {
            let (data, response) = try await session.data(for: request)
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   json["status"] as? String == "ok" {
                    return true
                }
            }
        } catch {
            // Not reachable
        }
        return false
    }

    // MARK: - HTTP /pair/verify

    private func executeVerifyRequest(host: String, port: Int, token: String, deviceName: String) async throws -> String? {
        guard let url = URL(string: "http://\(host):\(port)/pair/verify") else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 5

        let body: [String: String] = [
            "token": token,
            "deviceName": deviceName
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            return nil
        }

        if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
           let deviceId = json["deviceId"] as? String {
            return deviceId
        }
        return nil
    }

    // MARK: - Bonjour / mDNS Resolution

    private func resolveViaMdns(serviceName: String) async -> (ip: String, port: Int)? {
        return await withCheckedContinuation { continuation in
            let browser = NetServiceBrowser()
            let delegate = SingleResolveDelegate(targetName: serviceName) { result in
                continuation.resume(returning: result)
            }
            // Retain delegate during resolution
            objc_setAssociatedObject(browser, "resolveDelegate", delegate, .OBJC_ASSOCIATION_RETAIN)
            browser.delegate = delegate
            browser.searchForServices(ofType: "_photobackup._tcp.", inDomain: "local.")

            DispatchQueue.global().asyncAfter(deadline: .now() + 3.0) {
                delegate.finish(with: nil)
            }
        }
    }
}

private final class SingleResolveDelegate: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    private let targetName: String
    private var completion: (((ip: String, port: Int)?) -> Void)?
    private var resolvingServices: [NetService] = []
    private var isFinished = false

    init(targetName: String, completion: @escaping ((ip: String, port: Int)?) -> Void) {
        self.targetName = targetName
        self.completion = completion
        super.init()
    }

    func finish(with result: (ip: String, port: Int)?) {
        guard !isFinished else { return }
        isFinished = true
        for service in resolvingServices {
            service.stop()
        }
        resolvingServices.removeAll()
        completion?(result)
        completion = nil
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        if service.name == targetName || targetName.isEmpty {
            service.delegate = self
            resolvingServices.append(service)
            service.resolve(withTimeout: 3.0)
        }
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let addresses = sender.addresses, !addresses.isEmpty else { return }
        for data in addresses {
            var storage = sockaddr_storage()
            (data as NSData).getBytes(&storage, length: MemoryLayout<sockaddr_storage>.size)

            if storage.ss_family == sa_family_t(AF_INET) {
                var addr4 = withUnsafePointer(to: &storage) {
                    $0.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
                }
                var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                inet_ntop(AF_INET, &addr4.sin_addr, &buffer, socklen_t(INET_ADDRSTRLEN))
                let ip = String(cString: buffer)
                let port = Int(sender.port)
                finish(with: (ip, port))
                return
            }
        }
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String : NSNumber]) {
        // Did not resolve
    }
}
