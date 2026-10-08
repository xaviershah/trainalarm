import Foundation

struct Coordinate: Equatable {
    let latitude: Double
    let longitude: Double
}

enum Geo {
    static let earthRadiusMetres = 6_371_000.0

    /// Great-circle (Haversine) distance in metres.
    static func distanceMetres(fromLatitude lat1: Double, longitude lon1: Double, toLatitude lat2: Double, longitude lon2: Double) -> Double {
        let phi1 = lat1 * .pi / 180
        let phi2 = lat2 * .pi / 180
        let deltaPhi = (lat2 - lat1) * .pi / 180
        let deltaLambda = (lon2 - lon1) * .pi / 180
        let a = sin(deltaPhi / 2) * sin(deltaPhi / 2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        return 2 * earthRadiusMetres * asin(min(1, sqrt(a)))
    }
}
