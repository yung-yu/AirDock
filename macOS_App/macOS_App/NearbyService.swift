import Foundation
import AppKit
import NearbyConnections
import Network
import SystemConfiguration
import Darwin
import CryptoKit

struct MacAppInfo: Codable, Identifiable, Hashable {
    var id: String { bundleId }
    let name: String
    let bundleId: String
}

struct AppPayload: Codable {
    let type: String // "APP_LIST", "OPEN_APP", "PAIRING_REQUEST", "PAIRING_RESPONSE", "VERIFY_PAIRING", "CHALLENGE", "VERIFY_RESPONSE", "SWITCH_SPACE", "KILL_APP", "TRACKPAD"
    var apps: [MacAppInfo]? = nil
    var bundleId: String? = nil
    var uuid: String? = nil
    var token: String? = nil
    var challenge: String? = nil
    var direction: String? = nil
    var action: String? = nil
    var dx: Float? = nil
    var dy: Float? = nil
    var button: String? = nil
}

class NearbyService: NSObject, ObservableObject, ConnectionManagerDelegate, AdvertiserDelegate {
    @Published var connectionStatus = "Disconnected"
    @Published var verificationCode: String? = nil
    @Published var connectedEndpoint: EndpointID? = nil
    @Published var installedApps: [MacAppInfo] = []
    
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
    private var pendingChallenges: [EndpointID: String] = [:]
    private var verificationTimers: [EndpointID: DispatchWorkItem] = [:]
    private var autoAcceptedConnections: Set<EndpointID> = []
    
    private var connectionManager: ConnectionManager!
    private var advertiser: Advertiser!
    private let serviceId = "com.andy.macdock"
    
    private var tempVerificationHandler: ((Bool) -> Void)?
    
    private func sha256(_ input: String) -> String {
        let inputData = Data(input.utf8)
        let hashed = SHA256.hash(data: inputData)
        return hashed.compactMap { String(format: "%02x", $0) }.joined()
    }
    
    override init() {
        super.init()
        connectionManager = ConnectionManager(serviceID: serviceId, strategy: .pointToPoint)
        connectionManager.delegate = self
        
        advertiser = Advertiser(connectionManager: connectionManager)
        advertiser.delegate = self
        
        refreshInstalledApps()
    }
    
    func refreshInstalledApps() {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            let apps = self.getInstalledApps()
            DispatchQueue.main.async {
                self.installedApps = apps
                for endpointId in self.authorizedEndpoints {
                    self.sendAppList(to: endpointId)
                }
            }
        }
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
        let appList = installedApps
        let payload = AppPayload(type: "APP_LIST", apps: appList, bundleId: nil, uuid: nil, token: nil, challenge: nil, direction: nil)
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
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if let remoteName = String(data: context, encoding: .utf8) {
                self.pendingConnections[endpointID] = remoteName
            }
            connectionRequestHandler(true)
        }
    }
    
    // MARK: - ConnectionManagerDelegate
    func connectionManager(_ connectionManager: ConnectionManager, didReceive verificationCode: String, from endpointID: EndpointID, verificationHandler: @escaping (Bool) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let remoteName = self.pendingConnections[endpointID] ?? ""
            let parts = remoteName.split(separator: "|")
            let remoteUuid = parts.count > 1 ? String(parts[1]) : nil
            
            if let uuid = remoteUuid, self.pairedDevices[uuid] != nil {
                // Already paired, auto-accept
                self.autoAcceptedConnections.insert(endpointID)
                verificationHandler(true)
            } else {
                // New pairing: show verification code
                self.verificationCode = verificationCode
                self.connectionStatus = "Verifying..."
                self.tempVerificationHandler = verificationHandler
            }
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
                
                let remoteName = self.pendingConnections[endpointID] ?? ""
                let parts = remoteName.split(separator: "|")
                let remoteUuid = parts.count > 1 ? String(parts[1]) : nil
                
                if let uuid = remoteUuid, self.pairedDevices[uuid] != nil {
                    // It is a paired connection. Generate challenge
                    let macNonce = UUID().uuidString
                    self.pendingChallenges[endpointID] = macNonce
                    
                    // Setup verification timeout
                    let timer = DispatchWorkItem { [weak self] in
                        guard let self = self else { return }
                        if !self.authorizedEndpoints.contains(endpointID) {
                            print("Verification timeout for \(endpointID). Disconnecting.")
                            self.connectionManager.disconnect(from: endpointID)
                        }
                    }
                    self.verificationTimers[endpointID] = timer
                    DispatchQueue.main.asyncAfter(deadline: .now() + 10.0, execute: timer)
                    
                    let challengePayload = AppPayload(type: "CHALLENGE", apps: nil, bundleId: nil, uuid: nil, token: macNonce, challenge: nil, direction: nil)
                    if let data = try? JSONEncoder().encode(challengePayload) {
                        _ = self.connectionManager.send(data, to: [endpointID])
                    }
                }
            case .disconnected:
                if self.connectedEndpoint == endpointID {
                    self.connectedEndpoint = nil
                    self.authorizedEndpoints.remove(endpointID)
                    self.pendingConnections.removeValue(forKey: endpointID)
                    self.pendingChallenges.removeValue(forKey: endpointID)
                    self.autoAcceptedConnections.remove(endpointID)
                    self.verificationTimers[endpointID]?.cancel()
                    self.verificationTimers.removeValue(forKey: endpointID)
                    self.start()
                }
            case .rejected:
                self.connectionStatus = "Rejected"
                self.pendingChallenges.removeValue(forKey: endpointID)
                self.autoAcceptedConnections.remove(endpointID)
                self.verificationTimers[endpointID]?.cancel()
                self.verificationTimers.removeValue(forKey: endpointID)
            @unknown default:
                break
            }
        }
    }
    
    func connectionManager(_ connectionManager: ConnectionManager, didReceive data: Data, withID payloadID: PayloadID, from endpointID: EndpointID) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            guard let payload = try? JSONDecoder().decode(AppPayload.self, from: data) else { return }
            switch payload.type {
            case "PAIRING_REQUEST":
                if self.autoAcceptedConnections.contains(endpointID) {
                    print("Security Warning: PAIRING_REQUEST received on auto-accepted connection \(endpointID). Disconnecting and unpairing to allow re-pairing.")
                    if let clientUuid = payload.uuid {
                        var paired = self.pairedDevices
                        paired.removeValue(forKey: clientUuid)
                        self.pairedDevices = paired
                    }
                    self.connectionManager.disconnect(from: endpointID)
                    break
                }
                if let clientUuid = payload.uuid {
                    let token = UUID().uuidString
                    var paired = self.pairedDevices
                    paired[clientUuid] = token
                    self.pairedDevices = paired
                    
                    let response = AppPayload(type: "PAIRING_RESPONSE", apps: nil, bundleId: nil, uuid: self.myUUID, token: token, challenge: nil, direction: nil)
                    if let responseData = try? JSONEncoder().encode(response) {
                        _ = self.connectionManager.send(responseData, to: [endpointID])
                    }
                    self.authorizedEndpoints.insert(endpointID)
                    self.connectionStatus = "Connected"
                    self.sendAppList(to: endpointID)
                }
            case "VERIFY_PAIRING":
                if !self.autoAcceptedConnections.contains(endpointID) {
                    print("Security Warning: VERIFY_PAIRING received on manually pairing connection \(endpointID). Disconnecting.")
                    self.connectionManager.disconnect(from: endpointID)
                    break
                }
                if let clientUuid = payload.uuid, let clientResponse = payload.token, let androidNonce = payload.challenge {
                    guard let macNonce = self.pendingChallenges[endpointID],
                          let expectedToken = self.pairedDevices[clientUuid] else {
                        print("Verification context missing for \(clientUuid)")
                        self.connectionManager.disconnect(from: endpointID)
                        break
                    }
                    let expectedResponse = self.sha256(macNonce + expectedToken)
                    if clientResponse == expectedResponse {
                        // Client is successfully verified!
                        // Now respond to client's challenge
                        let macResponse = self.sha256(androidNonce + expectedToken)
                        let responsePayload = AppPayload(type: "VERIFY_RESPONSE", apps: nil, bundleId: nil, uuid: nil, token: macResponse, challenge: nil, direction: nil)
                        
                        // Clean up challenge and timer
                        self.pendingChallenges.removeValue(forKey: endpointID)
                        self.verificationTimers[endpointID]?.cancel()
                        self.verificationTimers.removeValue(forKey: endpointID)
                        
                        if let responseData = try? JSONEncoder().encode(responsePayload) {
                            _ = self.connectionManager.send(responseData, to: [endpointID])
                        }
                        
                        self.authorizedEndpoints.insert(endpointID)
                        self.connectionStatus = "Connected"
                        self.sendAppList(to: endpointID)
                    } else {
                        print("Verification challenge-response failed for \(clientUuid)")
                        self.pendingChallenges.removeValue(forKey: endpointID)
                        self.verificationTimers[endpointID]?.cancel()
                        self.verificationTimers.removeValue(forKey: endpointID)
                        self.connectionManager.disconnect(from: endpointID)
                    }
                } else {
                    print("Malformed VERIFY_PAIRING payload")
                    self.connectionManager.disconnect(from: endpointID)
                }
            case "OPEN_APP":
                if self.authorizedEndpoints.contains(endpointID), let bundleId = payload.bundleId {
                    self.launchApp(bundleId: bundleId)
                }
            case "SWITCH_SPACE":
                if !self.authorizedEndpoints.contains(endpointID) { return }
                if let direction = payload.direction {
                    print("Received SWITCH_SPACE command: \(direction)")
                    self.switchSpace(direction: direction)
                }
            case "KILL_APP":
                if self.authorizedEndpoints.contains(endpointID), let bundleId = payload.bundleId {
                    print("Received KILL_APP command for bundleId: \(bundleId)")
                    DispatchQueue.main.async {
                        let apps = NSRunningApplication.runningApplications(withBundleIdentifier: bundleId)
                        for app in apps {
                            app.terminate()
                        }
                    }
                }
            case "TRACKPAD":
                if self.authorizedEndpoints.contains(endpointID), let action = payload.action {
                    if action == "stop" || action == "clear" {
                        self.clearTrackpadQueue()
                    } else {
                        self.enqueueTrackpadEvent(payload)
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
    
    private func switchSpace(direction: String) {
        print("Executing switchSpace for direction: \(direction)")
        let keyCode = (direction == "left") ? 123 : 124
        
        // 嘗試使用 AppleScript 呼叫 System Events，這能給出最詳細的錯誤訊息
        let script = "tell application \"System Events\" to key code \(keyCode) using control down"
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        process.arguments = ["-e", script]
        
        let pipe = Pipe()
        process.standardError = pipe
        process.standardOutput = pipe
        
        do {
            try process.run()
            process.waitUntilExit()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            if let output = String(data: data, encoding: .utf8), !output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                print("⚠️ 切換桌面錯誤或輸出: \(output)")
            } else {
                print("✅ 成功送出切換桌面指令 (\(direction))")
            }
        } catch {
            print("❌ 執行 AppleScript 失敗: \(error)")
        }
    }

    private let trackpadSerialQueue = DispatchQueue(label: "com.andy.macdock.trackpad", qos: .userInteractive)
    private var pendingTrackpadEvents: [AppPayload] = []
    private var isProcessingTrackpad = false
    
    private func enqueueTrackpadEvent(_ payload: AppPayload) {
        trackpadSerialQueue.async { [weak self] in
            guard let self = self else { return }
            self.pendingTrackpadEvents.append(payload)
            if !self.isProcessingTrackpad {
                self.processTrackpadQueue()
            }
        }
    }
    
    private func clearTrackpadQueue() {
        trackpadSerialQueue.async { [weak self] in
            guard let self = self else { return }
            self.pendingTrackpadEvents.removeAll()
            print("🧹 Cleared trackpad event queue")
        }
    }
    
    private func processTrackpadQueue() {
        self.isProcessingTrackpad = true
        while !self.pendingTrackpadEvents.isEmpty {
            let payload = self.pendingTrackpadEvents.removeFirst()
            if let action = payload.action {
                self.handleTrackpadEvent(action: action, dx: payload.dx, dy: payload.dy, button: payload.button)
            }
        }
        self.isProcessingTrackpad = false
    }

    private func handleTrackpadEvent(action: String, dx: Float?, dy: Float?, button: String?) {
        let currentLocation = CGEvent(source: nil)?.location ?? .zero
        
        if action == "move" {
            guard let dx = dx, let dy = dy else { return }
            let newLocation = CGPoint(x: currentLocation.x + CGFloat(dx), y: currentLocation.y + CGFloat(dy))
            if let moveEvent = CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: newLocation, mouseButton: .left) {
                moveEvent.post(tap: .cghidEventTap)
            }
        } else if action == "click" {
            if let mouseDownEvent = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: currentLocation, mouseButton: .left),
               let mouseUpEvent = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: currentLocation, mouseButton: .left) {
                mouseDownEvent.post(tap: .cghidEventTap)
                mouseUpEvent.post(tap: .cghidEventTap)
            }
        }
    }
}
