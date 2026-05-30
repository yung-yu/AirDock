import SwiftUI

struct ContentView: View {
    @StateObject private var service = NearbyService()
    @State private var searchText = ""
    
    var body: some View {
        HStack(spacing: 0) {
            // Left Sidebar: Connection Controls & Status
            VStack(alignment: .leading, spacing: 20) {
                // Header
                VStack(alignment: .leading, spacing: 4) {
                    Text("AirDock")
                        .font(.system(size: 28, weight: .black))
                        .foregroundColor(.white)
                    Text("Nearby Server")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(Color(red: 0.2, green: 0.8, blue: 0.9))
                }
                
                Divider()
                    .background(Color.white.opacity(0.1))
                
                // Server State Card
                VStack(alignment: .leading, spacing: 12) {
                    Text("Server Status")
                        .font(.headline)
                        .foregroundColor(.gray)
                    
                    HStack(spacing: 8) {
                        Circle()
                            .fill(statusColor)
                            .frame(width: 10, height: 10)
                        
                        Text(service.connectionStatus)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.white)
                    }
                    .padding(.vertical, 8)
                    .padding(.horizontal, 12)
                    .background(Color.white.opacity(0.05))
                    .cornerRadius(8)
                }
                
                // Action Buttons
                VStack(spacing: 12) {
                    Button(action: {
                        if service.connectionStatus == "Disconnected" {
                            service.start()
                        } else {
                            service.stop()
                        }
                    }) {
                        HStack {
                            Image(systemName: service.connectionStatus == "Disconnected" ? "play.fill" : "stop.fill")
                            Text(service.connectionStatus == "Disconnected" ? "Start Advertising" : "Stop Server")
                                .fontWeight(.bold)
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(service.connectionStatus == "Disconnected" ? Color.indigo : Color.red)
                        .foregroundColor(.white)
                        .cornerRadius(12)
                    }
                    .buttonStyle(PlainButtonStyle())
                }
                
                // PIN Verification Block
                if let pin = service.verificationCode {
                    VStack(spacing: 12) {
                        Text("Verify Connection PIN")
                            .font(.headline)
                            .foregroundColor(.white)
                        
                        Text(pin)
                            .font(.system(size: 32, weight: .black, design: .monospaced))
                            .foregroundColor(Color(red: 0.2, green: 0.8, blue: 0.9))
                            .padding(.vertical, 8)
                            .padding(.horizontal, 24)
                            .background(Color.black.opacity(0.3))
                            .cornerRadius(8)
                        
                        Text("Confirm this matches the PIN on your phone:")
                            .font(.caption)
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                        
                        HStack(spacing: 16) {
                            Button(action: { service.confirmConnection(accept: false) }) {
                                Text("Reject")
                                    .fontWeight(.semibold)
                                    .foregroundColor(.white)
                                    .padding(.vertical, 8)
                                    .padding(.horizontal, 20)
                                    .background(Color.red.opacity(0.8))
                                    .cornerRadius(8)
                            }
                            .buttonStyle(PlainButtonStyle())
                            
                            Button(action: { service.confirmConnection(accept: true) }) {
                                Text("Confirm")
                                    .fontWeight(.semibold)
                                    .foregroundColor(.white)
                                    .padding(.vertical, 8)
                                    .padding(.horizontal, 20)
                                    .background(Color.green.opacity(0.8))
                                    .cornerRadius(8)
                            }
                            .buttonStyle(PlainButtonStyle())
                        }
                    }
                    .padding()
                    .background(Color.white.opacity(0.05))
                    .cornerRadius(16)
                    .transition(.scale.combined(with: .opacity))
                }
                
                Spacer()
                
                // Footer
                Text("Version 1.0.0")
                    .font(.caption)
                    .foregroundColor(.gray)
            }
            .padding(24)
            .frame(width: 320)
            .background(Color(red: 0.08, green: 0.1, blue: 0.15))
            
            // Right Pane: Scan & List Detected Local Applications
            VStack(alignment: .leading, spacing: 20) {
                HStack {
                    Text("Applications List")
                        .font(.title2)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                    
                    Spacer()
                    
                    Text("\(filteredApps.count) apps detected")
                        .font(.caption)
                        .foregroundColor(.gray)
                        .padding(.vertical, 4)
                        .padding(.horizontal, 8)
                        .background(Color.white.opacity(0.05))
                        .cornerRadius(6)
                }
                
                // Search Bar
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.gray)
                    TextField("Search applications...", text: $searchText)
                        .textFieldStyle(PlainTextFieldStyle())
                        .foregroundColor(.white)
                }
                .padding(10)
                .background(Color.white.opacity(0.05))
                .cornerRadius(8)
                
                // List of apps
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(filteredApps) { app in
                            HStack {
                                // Circular app icon replacement
                                Text(app.name.prefix(1).uppercased())
                                    .font(.headline)
                                    .foregroundColor(Color(red: 0.2, green: 0.8, blue: 0.9))
                                    .frame(width: 36, height: 36)
                                    .background(Color.indigo.opacity(0.2))
                                    .cornerRadius(8)
                                
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(app.name)
                                        .font(.system(size: 14, weight: .bold))
                                        .foregroundColor(.white)
                                    Text(app.bundleId)
                                        .font(.system(size: 11))
                                        .foregroundColor(.gray)
                                }
                                
                                Spacer()
                                
                                // Test Launch Button
                                Button(action: { service.launchApp(bundleId: app.bundleId) }) {
                                    Image(systemName: "arrow.up.forward.app.fill")
                                        .foregroundColor(Color(red: 0.2, green: 0.8, blue: 0.9))
                                        .frame(width: 32, height: 32)
                                        .background(Color.white.opacity(0.05))
                                        .cornerRadius(8)
                                }
                                .buttonStyle(PlainButtonStyle())
                            }
                            .padding(10)
                            .background(Color.white.opacity(0.02))
                            .cornerRadius(10)
                        }
                    }
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(red: 0.12, green: 0.14, blue: 0.2))
        }
        .frame(minWidth: 720, minHeight: 480)
        .preferredColorScheme(.dark)
        .onAppear {
            checkAccessibilityPermission()
        }
    }
    
    private func checkAccessibilityPermission() {
        let options: NSDictionary = [kAXTrustedCheckOptionPrompt.takeRetainedValue() as NSString: true]
        let accessEnabled = AXIsProcessTrustedWithOptions(options)
        if !accessEnabled {
            print("Accessibility permission not granted. Please enable it in System Settings.")
        }
    }
    
    private var filteredApps: [MacAppInfo] {
        let allApps = service.installedApps
        if searchText.isEmpty {
            return allApps
        } else {
            return allApps.filter { $0.name.localizedCaseInsensitiveContains(searchText) }
        }
    }
    
    private var statusColor: Color {
        switch service.connectionStatus {
        case "Advertising...":
            return .cyan
        case "Verifying...":
            return .indigo
        case let s where s.hasPrefix("Connected"):
            return .green
        default:
            return .red
        }
    }
}
#Preview {
    ContentView()
}
