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
    let type: String // "APP_LIST" or "OPEN_APP"
    let apps: [MacAppInfo]?
    let bundleId: String?
}

class NearbyService: NSObject, ObservableObject, ConnectionManagerDelegate, AdvertiserDelegate {
    @Published var connectionStatus = "Disconnected"
    @Published var verificationCode: String? = nil
    @Published var connectedEndpoint: EndpointID? = nil
    
    private var connectionManager: ConnectionManager!
    private var advertiser: Advertiser!
    private let serviceId = "com.antigravity.macdock"
    
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
        advertiser.startAdvertising(using: HostName().data(using: .utf8)!)
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
        let payload = AppPayload(type: "APP_LIST", apps: appList, bundleId: nil)
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
        // Automatically accept the request, verification happens in ConnectionManagerDelegate
        connectionRequestHandler(true)
    }
    
    // MARK: - ConnectionManagerDelegate
    func connectionManager(_ connectionManager: ConnectionManager, didReceive verificationCode: String, from endpointID: EndpointID, verificationHandler: @escaping (Bool) -> Void) {
        DispatchQueue.main.async {
            self.verificationCode = verificationCode
            self.connectionStatus = "Verifying..."
        }
        self.tempVerificationHandler = verificationHandler
    }
    
    func connectionManager(_ connectionManager: ConnectionManager, didChangeTo state: ConnectionState, for endpointID: EndpointID) {
        DispatchQueue.main.async {
            switch state {
            case .connecting:
                self.connectionStatus = "Connecting..."
            case .connected:
                self.connectionStatus = "Connected to \(endpointID)"
                self.connectedEndpoint = endpointID
                self.advertiser.stopAdvertising()
                self.sendAppList(to: endpointID)
            case .disconnected:
                if self.connectedEndpoint == endpointID {
                    self.connectedEndpoint = nil
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
            if payload.type == "OPEN_APP", let bundleId = payload.bundleId {
                DispatchQueue.main.async {
                    self.launchApp(bundleId: bundleId)
                }
            }
        }
    }
    
    func connectionManager(_ connectionManager: ConnectionManager, didReceive stream: InputStream, withID payloadID: PayloadID, from endpointID: EndpointID, cancellationToken token: CancellationToken) {}
    func connectionManager(_ connectionManager: ConnectionManager, didStartReceivingResourceWithID payloadID: PayloadID, from endpointID: EndpointID, at localURL: URL, withName name: String, cancellationToken token: CancellationToken) {}
    func connectionManager(_ connectionManager: ConnectionManager, didReceiveTransferUpdate update: TransferUpdate, from endpointID: EndpointID, forPayload payloadID: PayloadID) {}
    
}
