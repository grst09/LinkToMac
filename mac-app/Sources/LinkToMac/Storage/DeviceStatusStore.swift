import Foundation
import Observation

/// Holds the phone's last-reported battery status. Nil until the first `device.status`
/// arrives (right after `helloAck`, and again on every `ACTION_BATTERY_CHANGED`).
@Observable
final class DeviceStatusStore {
    private(set) var batteryPercent: Int?
    private(set) var isCharging = false

    func update(_ payload: DeviceStatusPayload) {
        batteryPercent = payload.batteryPercent
        isCharging = payload.isCharging
    }
}
