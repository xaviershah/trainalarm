Stage 1: TrainDataProvider protocol + the RTT (Realtime Trains) implementation. See docs/spec.md §2-3.
`StationDirectory.swift`: loads the bundled `stations.json` (NaPTAN, see `data/STATIONS-SOURCE.md`); looks up a station by CRS and searches by name. Not yet wired into `RttProvider.searchStations`.
