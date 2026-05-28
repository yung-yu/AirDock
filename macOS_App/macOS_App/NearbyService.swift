import Foundation
import AppKit
import NearbyConnections
import Network
import SystemConfiguration
import Darwin

struct MacAppInfo: Codable, Identifiable, Hashable {
    var id: String { bundleId }
    let name: String
    let bundleId: String
}

struct AppPayload: Codable {
    let type: String // "APP_LIST", "OPEN_APP", "PAIRING_REQUEST", "PAIRING_RESPONSE", "VERIFY_PAIRING"
    let apps: [MacAppInfo]?
    let bundleId: String?
    let uuid: String?
    let token: String?
}

class NearbyService: NSObject, ObservableObject, ConnectionManagerDelegate, AdvertiserDelegate {
    @Published var connectionStatus = "Disconnected"
    @Published var verificationCode: String? = nil
    @Published var connectedEndpoint: EndpointID? = nil
    
    private var myUUID: String {
        if let uuid = UserDefaults.standard.string(forKey: "macdock_my_uuid") {
            return uuid
        }
        let uuid = UUID().uuidString
        UserDefaults.standard.set(uuid, forKey: "macdock_my_uuid")
        return uuid
    }
    
    private var pairedDevices: [String: String] {
        get {
            UserDefaults.standard.dictionary(forKey: "macdock_paired_devices") as? [String: String] ?? [:]
        }
        set {
            UserDefaults.standard.set(newValue, forKey: "macdock_paired_devices")
        }
    }
    
    private var pendingConnections: [EndpointID: String] = [:]
    private var authorizedEndpoints: Set<EndpointID> = []
    
    private var connectionManager: ConnectionManager!
    private var advertiser: Advertiser!
    private let serviceId = "com.andy.macdock"
    
    private var tempVerificationHandler: ((Bool) -> Void)?
    
    override init() {
        super.init()
        connectionManager = ConnectionManager(serviceID: serviceId, strategy: .pointToPoint)
        connectionManager.delegate = self
        
        advertiser = Advertiser(connectionManager: connectionManager)
        advertiser.delegate = self
    }
    
    func start() {
        DispatchQueue.main.async {
            self.connectionStatus = "Advertising..."
        }
        let nameWithUuid = "\(HostName())|\(myUUID)"
        advertiser.startAdvertising(using: nameWithUuid.data(using: .utf8)!)
    }
    
    func stop() {
        advertiser.stopAdvertising()
        connectionManager.delegate = nil
        // Reinitialize to ensure clean state
        connectionManager = ConnectionManager(serviceID: serviceId, strategy: .pointToPoint)
        connectionManager.delegate = self
        advertiser = Advertiser(connectionManager: connectionManager)
        advertiser.delegate = self
        
        DispatchQueue.main.async {
            self.connectionStatus = "Disconnected"
            self.connectedEndpoint = nil
            self.verificationCode = nil
        }
    }
    
    private func HostName() -> String {
        return Host.current().localizedName ?? "My Mac"
    }
    
    // Enumerate installed applications
    func getInstalledApps() -> [MacAppInfo] {
        var apps: [MacAppInfo] = []
        let fileManager = FileManager.default
        let searchPaths = ["/Applications", "/System/Applications"]
        
        for path in searchPaths {
            let url = URL(fileURLWithPath: path)
            if let enumerator = fileManager.enumerator(at: url,
                                                       includingPropertiesForKeys: [.nameKey],
                                                       options: [.skipsSubdirectoryDescendants, .skipsHiddenFiles]) {
                for case let fileURL as URL in enumerator {
                    if fileURL.pathExtension == "app" {
                        let appName = fileURL.deletingPathExtension().lastPathComponent
                        // Skip common system helper binaries that shouldn't be listed as launchable apps
                        if appName.hasPrefix("Install ") || appName.contains("Uninstall") {
                            continue
                        }
                        if let bundle = Bundle(url: fileURL), let bundleId = bundle.bundleIdentifier {
                            // Deduplicate by bundleId
                            if !apps.contains(where: { $0.bundleId == bundleId }) {
                                apps.append(MacAppInfo(name: appName, bundleId: bundleId))
                            }
                        }
                    }
                }
            }
        }
        return apps.sorted(by: { $0.name.lowercased() < $1.name.lowercased() })
    }
    
    func sendAppList(to endpointId: EndpointID) {
        let appList = getInstalledApps()
        let payload = AppPayload(type: "APP_LIST", apps: appList, bundleId: nil, uuid: nil, token: nil)
        if let data = try? JSONEncoder().encode(payload) {
            _ = connectionManager.send(data, to: [endpointId])
        }
    }
    
    func launchApp(bundleId: String) {
        guard let appURL = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleId) else {
            print("App URL not found for \(bundleId)")
            return
        }
        let config = NSWorkspace.OpenConfiguration()
        config.activates = true
        NSWorkspace.shared.openApplication(at: appURL, configuration: config) { _, error in
            if let error = error {
                print("Failed to open app \(bundleId): \(error.localizedDescription)")
            }
        }
    }
    
    func confirmConnection(accept: Bool) {
        tempVerificationHandler?(accept)
        tempVerificationHandler = nil
        DispatchQueue.main.async {
            self.verificationCode = nil
        }
    }
    
    // MARK: - AdvertiserDelegate
    func advertiser(_ advertiser: Advertiser, didReceiveConnectionRequestFrom endpointID: EndpointID, with context: Data, connectionRequestHandler: @escaping (Bool) -> Void) {
        if let remoteName = String(data: context, encoding: .utf8) {
            pendingConnections[endpointID] = remoteName
        }
        connectionRequestHandler(true)
    }
    
    // MARK: - ConnectionManagerDelegate
    func connectionManager(_ connectionManager: ConnectionManager, didReceive verificationCode: String, from endpointID: EndpointID, verificationHandler: @escaping (Bool) -> Void) {
        let remoteName = pendingConnections[endpointID] ?? ""
        let parts = remoteName.split(separator: "|")
        let remoteUuid = parts.count > 1 ? String(parts[1]) : nil
        
        if let uuid = remoteUuid, pairedDevices[uuid] != nil {
            // Already paired, auto-accept
            verificationHandler(true)
        } else {
            // New pairing: show verification code
            DispatchQueue.main.async {
                self.verificationCode = verificationCode
                self.connectionStatus = "Verifying..."
            }
            self.tempVerificationHandler = verificationHandler
        }
    }
    
    func connectionManager(_ connectionManager: ConnectionManager, didChangeTo state: ConnectionState, for endpointID: EndpointID) {
        DispatchQueue.main.async {
            switch state {
            case .connecting:
                self.connectionStatus = "Connecting..."
            case .connected:
                self.connectionStatus = "Verifying..."
                self.connectedEndpoint = endpointID
                self.advertiser.stopAdvertising()
            case .disconnected:
                if self.connectedEndpoint == endpointID {
                    self.connectedEndpoint = nil
                    self.authorizedEndpoints.remove(endpointID)
                    self.pendingConnections.removeValue(forKey: endpointID)
                    self.start()
                }
            case .rejected:
                self.connectionStatus = "Rejected"
            @unknown default:
                break
            }
        }
    }
    
    func connectionManager(_ connectionManager: ConnectionManager, didReceive data: Data, withID payloadID: PayloadID, from endpointID: EndpointID) {
        if let payload = try? JSONDecoder().decode(AppPayload.self, from: data) {
            switch payload.type {
            case "PAIRING_REQUEST":
                if let clientUuid = payload.uuid {
                    let token = UUID().uuidString
                    var paired = pairedDevices
                    paired[clientUuid] = token
                    pairedDevices = paired
                    
                    let response = AppPayload(type: "PAIRING_RESPONSE", apps: nil, bundleId: nil, uuid: myUUID, token: token)
                    if let responseData = try? JSONEncoder().encode(response) {
                        _ = connectionManager.send(responseData, to: [endpointID])
                    }
                    authorizedEndpoints.insert(endpointID)
                    DispatchQueue.main.async {
                        self.connectionStatus = "Connected"
                    }
                    self.sendAppList(to: endpointID)
                }
            case "VERIFY_PAIRING":
                if let clientUuid = payload.uuid, let clientToken = payload.token {
                    if pairedDevices[clientUuid] == clientToken {
                        authorizedEndpoints.insert(endpointID)
                        DispatchQueue.main.async {
                            self.connectionStatus = "Connected"
                        }
                        self.sendAppList(to: endpointID)
                    } else {
                        print("Verification failed for \(clientUuid)")
                        connectionManager.disconnect(from: endpointID)
                    }
                }
            case "OPEN_APP":
                if authorizedEndpoints.contains(endpointID), let bundleId = payload.bundleId {
                    DispatchQueue.main.async {
                        self.launchApp(bundleId: bundleId)
                    }
                }
            default:
                break
            }
        }
    }
    
    func connectionManager(_ connectionManager: ConnectionManager, didReceive stream: InputStream, withID payloadID: PayloadID, from endpointID: EndpointID, cancellationToken token: CancellationToken) {}
    func connectionManager(_ connectionManager: ConnectionManager, didStartReceivingResourceWithID payloadID: PayloadID, from endpointID: EndpointID, at localURL: URL, withName name: String, cancellationToken token: CancellationToken) {}
    func connectionManager(_ connectionManager: ConnectionManager, didReceiveTransferUpdate update: TransferUpdate, from endpointID: EndpointID, forPayload payloadID: PayloadID) {}
    
}
