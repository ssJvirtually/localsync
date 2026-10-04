import Foundation

public struct QrPayload: Codable {
    public let service: String
    public let token: String
    public let pcName: String
    public let port: Int
    public let localIp: String
    public let ips: [String]?

    public init(service: String, token: String, pcName: String, port: Int, localIp: String, ips: [String]? = nil) {
        self.service = service
        self.token = token
        self.pcName = pcName
        self.port = port
        self.localIp = localIp
        self.ips = ips
    }
}
