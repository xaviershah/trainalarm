import Foundation

extension StationDirectory {
    /// The station closest to a point, or nil if the directory has none with coordinates.
    func nearest(latitude: Double, longitude: Double) -> Station? {
        func distance(_ station: Station) -> Double {
            Geo.distanceMetres(fromLatitude: latitude, longitude: longitude,
                               toLatitude: station.latitude, longitude: station.longitude)
        }
        return stations.filter(\.hasCoordinate).min { distance($0) < distance($1) }
    }
}
