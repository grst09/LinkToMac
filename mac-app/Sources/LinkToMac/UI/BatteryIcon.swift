import Foundation

/// SF Symbol name for a battery level, shared between SidebarDeviceCard and DeviceStatusView.
/// Deliberately doesn't try to pick a charging-specific glyph variant (e.g. a hypothetical
/// "battery.100.bolt") since an unrecognized SF Symbol name silently renders blank rather than
/// erroring — charging state is conveyed via color/text at the call site instead.
enum BatteryIcon {
    static func symbolName(percent: Int, isCharging: Bool) -> String {
        switch percent {
        case ..<13: return "battery.0"
        case 13..<38: return "battery.25"
        case 38..<63: return "battery.50"
        case 63..<88: return "battery.75"
        default: return "battery.100"
        }
    }
}
