import SwiftUI
import AppKit

@main
struct application: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    
    var body: some Scene {
        Settings {
            EmptyView()
        }
    }
}

class AppDelegate: NSObject, NSApplicationDelegate {
    var statusItem: NSStatusItem?
    var popover: NSPopover?
    
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Create popover
        let popover = NSPopover()
        popover.contentSize = NSSize(width: 720, height: 480)
        popover.behavior = .transient
        popover.contentViewController = NSHostingController(rootView: ContentView())
        self.popover = popover
        
        // Create status item
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        
        if let button = statusItem?.button {
            var image = NSImage(systemSymbolName: "airplaycomputer", accessibilityDescription: "AirDock")
            if image == nil {
                image = NSImage(systemSymbolName: "laptopcomputer.and.iphone", accessibilityDescription: "AirDock")
            }
            if image == nil {
                image = NSImage(systemSymbolName: "personalhotspot", accessibilityDescription: "AirDock")
            }
            if image == nil {
                image = NSImage(systemSymbolName: "link", accessibilityDescription: "AirDock")
            }
            
            if let image = image {
                image.isTemplate = true
                button.image = image
            } else {
                print("Failed to load any status bar symbol!")
            }
            button.action = #selector(togglePopover(_:))
            button.target = self
        }
    }
    
    @objc func togglePopover(_ sender: AnyObject?) {
        guard let button = statusItem?.button else { return }
        guard let popover = popover else { return }
        
        if popover.isShown {
            popover.performClose(sender)
        } else {
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            popover.contentViewController?.view.window?.makeKey()
        }
    }
}
