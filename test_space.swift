import Cocoa

func switchSpace(direction: String) {
    let keyCode: CGKeyCode = (direction == "left") ? 123 : 124
    
    guard let source = CGEventSource(stateID: .hidSystemState) else { return }
    guard let keyDown = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: true) else { return }
    guard let keyUp = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: false) else { return }
    
    keyDown.flags = .maskControl
    keyUp.flags = .maskControl
    
    keyDown.post(tap: .cghidEventTap)
    keyUp.post(tap: .cghidEventTap)
    print("Space switched: \(direction)")
}

let options: NSDictionary = [kAXTrustedCheckOptionPrompt.takeRetainedValue() as NSString: true]
let accessEnabled = AXIsProcessTrustedWithOptions(options)
if !accessEnabled {
    print("No accessibility permission!")
} else {
    print("Has accessibility permission")
}

switchSpace(direction: "right")
