import Foundation

public struct PairedServer: Identifiable, Hashable, Codable {
    public let id: Int64
    public let serviceName: String
    public let pcName: String
    public let token: String
    public let deviceId: String
    public let fallbackIp: String      // Comma-separated list of ip:port
    public let pairedAt: Int64

    public init(
        id: Int64 = 1,
        serviceName: String,
        pcName: String,
        token: String,
        deviceId: String,
        fallbackIp: String,
        pairedAt: Int64
    ) {
        self.id = id
        self.serviceName = serviceName
        self.pcName = pcName
        self.token = token
        self.deviceId = deviceId
        self.fallbackIp = fallbackIp
        self.pairedAt = pairedAt
    }
}
