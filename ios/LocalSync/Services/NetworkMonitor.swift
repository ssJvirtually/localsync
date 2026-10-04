import Foundation
import Network

public final class NetworkMonitor: ObservableObject {
    public static let shared = NetworkMonitor()

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.localsync.networkmonitor")

    @Published public private(set) var isConnected: Bool = false
    @Published public private(set) var isWifi: Bool = false
    @Published public private(set) var isCellular: Bool = false

    public init() {
        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                self?.isConnected = path.status == .satisfied
                self?.isWifi = path.usesInterfaceType(.wifi)
                self?.isCellular = path.usesInterfaceType(.cellular)
            }
        }
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }

    public static func isTailscaleIp(_ ip: String) -> Bool {
        let cleanIp = ip.replacingOccurrences(of: "[", with: "").replacingOccurrences(of: "]", with: "")
        guard cleanIp.hasPrefix("100.") else { return false }
        let parts = cleanIp.split(separator: ".")
        guard parts.count >= 2, let secondOctet = Int(parts[1]) else { return false }
        return secondOctet >= 64 && secondOctet <= 127
    }

    public func canSync(toHost: String, isCellularTailscaleAllowed: Bool) -> Bool {
        guard isConnected else { return false }
        if isWifi { return true }
        if isCellular {
            let hostClean = toHost.components(separatedBy: ":").first ?? toHost
            let isTailscale = Self.isTailscaleIp(hostClean)
            return isCellularTailscaleAllowed && isTailscale
        }
        return false
    }
}
