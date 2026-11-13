import CoreLocation
import Foundation

extension LocationFix {
    /// Core Location reports a negative horizontal accuracy for an invalid fix. It becomes infinite
    /// so the tracker's `maxFixAccuracy` check drops it.
    init(location: CLLocation) {
        self.init(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude,
                  timestamp: location.timestamp,
                  accuracy: location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : .infinity)
    }
}

/// Keeps the latest GPS fix for the tracker. Location while in use only; never background location.
final class CoreLocationSource: NSObject, LocationSource, CLLocationManagerDelegate {
    private let manager: CLLocationManager
    private let lock = NSLock()
    private var latest: LocationFix?

    init(manager: CLLocationManager = CLLocationManager()) {
        self.manager = manager
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    /// Call only after the user has allowed location while in use.
    func start() { manager.startUpdatingLocation() }
    func stop() { manager.stopUpdatingLocation() }

    func latestFix() -> LocationFix? {
        lock.lock()
        defer { lock.unlock() }
        return latest
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        lock.lock()
        latest = LocationFix(location: location)
        lock.unlock()
    }
}
