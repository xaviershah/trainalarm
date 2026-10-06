import Foundation

enum StationDirectoryError: Error, Equatable {
    case resourceMissing
    case malformed(String)
}

private final class BundleToken {}

/// Static UK station directory loaded from the bundled `stations.json`
/// (NaPTAN, Open Government Licence v3.0 - see data/STATIONS-SOURCE.md).
/// `Station.id` is the CRS code.
struct StationDirectory {
    let stations: [Station]
    private let byCRS: [String: Station]

    private init(stations: [Station]) {
        self.stations = stations
        self.byCRS = Dictionary(uniqueKeysWithValues: stations.map { ($0.id, $0) })
    }

    /// Throws rather than returning a partial directory.
    static func parse(_ data: Data) throws -> StationDirectory {
        guard let array = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else {
            throw StationDirectoryError.malformed("stations file was not a JSON array of objects")
        }
        var seen = Set<String>()
        var stations: [Station] = []
        for (index, object) in array.enumerated() {
            guard let crs = object["crs"] as? String,
                  let name = object["name"] as? String,
                  let lat = object["lat"] as? Double,
                  let lon = object["lon"] as? Double else {
                throw StationDirectoryError.malformed("record \(index) is missing crs, name, lat or lon")
            }
            guard seen.insert(crs).inserted else {
                throw StationDirectoryError.malformed("duplicate CRS \(crs)")
            }
            stations.append(Station(id: crs, name: name, latitude: lat, longitude: lon))
        }
        return StationDirectory(stations: stations)
    }

    static func bundledFileURL(bundle: Bundle? = nil) -> URL? {
        (bundle ?? Bundle(for: BundleToken.self)).url(forResource: "stations", withExtension: "json")
    }

    static func load(from bundle: Bundle? = nil) throws -> StationDirectory {
        guard let url = bundledFileURL(bundle: bundle) else { throw StationDirectoryError.resourceMissing }
        return try parse(Data(contentsOf: url))
    }

    func station(crs: String) -> Station? {
        byCRS[crs.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()]
    }

    /// Case-insensitive substring search; prefix matches first. Blank query returns nothing.
    func search(name: String) -> [Station] {
        let query = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !query.isEmpty else { return [] }
        let matches = stations.filter { $0.name.lowercased().contains(query) }
        let prefix = matches.filter { $0.name.lowercased().hasPrefix(query) }
        let rest = matches.filter { !$0.name.lowercased().hasPrefix(query) }
        return prefix.sorted { $0.name < $1.name } + rest.sorted { $0.name < $1.name }
    }
}
