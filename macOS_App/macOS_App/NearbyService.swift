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
    @Published var localIP: String? = nil
    
    private var connectionManager: ConnectionManager!
    private var advertiser: Advertiser!
    private let serviceId = "com.antigravity.macdock"
    
    private var tempVerificationHandler: ((Bool) -> Void)?
    private var tcpServer: TcpServer!
    
    override init() {
        super.init()
        connectionManager = ConnectionManager(serviceID: serviceId, strategy: .pointToPoint)
        connectionManager.delegate = self
        
        advertiser = Advertiser(connectionManager: connectionManager)
        advertiser.delegate = self
        
        setupTcpServer()
    }
    
    private func setupTcpServer() {
        tcpServer = TcpServer()
        tcpServer.onConnectionStateChanged = { [weak self] state in
            DispatchQueue.main.async {
                self?.connectionStatus = state
                if state == "Disconnected" && self?.localIP != nil {
                    // Re-advertise/listen
                    self?.connectionStatus = "Advertising..."
                }
            }
        }
        tcpServer.onDataReceived = { [weak self] data in
            self?.handleTcpData(data)
        }
        tcpServer.onAppListRequested = { [weak self] in
            self?.sendTcpAppList()
        }
    }
    
    func start() {
        DispatchQueue.main.async {
            self.connectionStatus = "Advertising..."
            self.localIP = self.getLocalIPAddress()
        }
        advertiser.startAdvertising(using: HostName().data(using: .utf8)!)
        tcpServer.start(port: 12345)
    }
    
    func stop() {
        advertiser.stopAdvertising()
        connectionManager.delegate = nil
        // Reinitialize to ensure clean state
        connectionManager = ConnectionManager(serviceID: serviceId, strategy: .pointToPoint)
        connectionManager.delegate = self
        advertiser = Advertiser(connectionManager: connectionManager)
        advertiser.delegate = self
        
        tcpServer.stop()
        
        DispatchQueue.main.async {
            self.connectionStatus = "Disconnected"
            self.connectedEndpoint = nil
            self.verificationCode = nil
            self.localIP = nil
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
    
    // MARK: - TCP Socket Handling
    private func handleTcpData(_ data: Data) {
        if let payload = try? JSONDecoder().decode(AppPayload.self, from: data) {
            if payload.type == "OPEN_APP", let bundleId = payload.bundleId {
                DispatchQueue.main.async {
                    self.launchApp(bundleId: bundleId)
                }
            }
        }
    }
    
    private func sendTcpAppList() {
        let appList = getInstalledApps()
        let payload = AppPayload(type: "APP_LIST", apps: appList, bundleId: nil)
        if let data = try? JSONEncoder().encode(payload) {
            tcpServer.send(data: data)
        }
    }
    
    private func getLocalIPAddress() -> String? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0 else { return nil }
        guard let firstAddr = ifaddr else { return nil }
        
        for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
            let flags = ptr.pointee.ifa_flags
            var addr = ptr.pointee.ifa_addr.pointee
            
            // Check for running IPv4 interface. Skip loopback (e.g. lo0)
            if (Int32(flags) & IFF_UP) == IFF_UP && (Int32(flags) & IFF_LOOPBACK) != IFF_LOOPBACK {
                if addr.sa_family == UInt8(AF_INET) {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(&addr, socklen_t(addr.sa_len), &hostname, socklen_t(hostname.count), nil, socklen_t(0), NI_NUMERICHOST) == 0 {
                        let name = String(cString: hostname)
                        let interfaceName = String(cString: ptr.pointee.ifa_name)
                        // Prefer Wi-Fi interface (typically en0)
                        if interfaceName == "en0" {
                            address = name
                            break
                        } else if address == nil {
                            address = name
                        }
                    }
                }
            }
        }
        freeifaddrs(ifaddr)
        return address
    }
}

// MARK: - TCP Socket Server
class TcpServer {
    private var listener: NWListener?
    private var connectedConnection: NWConnection?
    
    var onConnectionStateChanged: ((String) -> Void)?
    var onDataReceived: ((Data) -> Void)?
    var onAppListRequested: (() -> Void)?
    
    func start(port: UInt16 = 12345) {
        stop()
        do {
            let parameters = NWParameters.tcp
            parameters.allowLocalEndpointReuse = true
            let nwPort = NWEndpoint.Port(rawValue: port)!
            listener = try NWListener(using: parameters, on: nwPort)
            
            listener?.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    print("TCP Server listening on port \(port)")
                case .failed(let error):
                    print("TCP Server error: \(error)")
                default:
                    break
                }
            }
            
            listener?.newConnectionHandler = { [weak self] connection in
                self?.handleNewConnection(connection)
            }
            
            listener?.start(queue: .main)
        } catch {
            print("Failed to start NWListener: \(error)")
        }
    }
    
    func stop() {
        listener?.cancel()
        listener = nil
        connectedConnection?.cancel()
        connectedConnection = nil
    }
    
    private func handleNewConnection(_ connection: NWConnection) {
        connectedConnection?.cancel()
        connectedConnection = connection
        
        connection.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            switch state {
            case .ready:
                self.onConnectionStateChanged?("Connected (IP)")
                self.onAppListRequested?()
                self.receiveMessage(on: connection)
            case .failed(let error):
                print("TCP Connection failed: \(error)")
                self.onConnectionStateChanged?("Disconnected")
                self.connectedConnection = nil
            case .cancelled:
                self.onConnectionStateChanged?("Disconnected")
                self.connectedConnection = nil
            default:
                break
            }
        }
        connection.start(queue: .main)
    }
    
    private func receiveMessage(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if let error = error {
                print("TCP read length error: \(error)")
                connection.cancel()
                return
            }
            if isComplete {
                connection.cancel()
                return
            }
            guard let data = data, data.count == 4 else {
                connection.cancel()
                return
            }
            
            let length = Int(data.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian })
            guard length > 0 && length < 10 * 1024 * 1024 else {
                print("TCP invalid message length: \(length)")
                connection.cancel()
                return
            }
            
            connection.receive(minimumIncompleteLength: length, maximumLength: length) { [weak self] payloadData, _, isComplete, error in
                guard let self = self else { return }
                if let error = error {
                    print("TCP read payload error: \(error)")
                    connection.cancel()
                    return
                }
                guard let payloadData = payloadData, payloadData.count == length else {
                    connection.cancel()
                    return
                }
                
                self.onDataReceived?(payloadData)
                
                if !isComplete {
                    self.receiveMessage(on: connection)
                }
            }
        }
    }
    
    func send(data: Data) {
        guard let connection = connectedConnection else { return }
        var length = UInt32(data.count).bigEndian
        let lengthData = Data(bytes: &length, count: 4)
        let fullPayload = lengthData + data
        
        connection.send(content: fullPayload, completion: .contentProcessed({ error in
            if let error = error {
                print("TCP Send error: \(error)")
            }
        }))
    }
}
