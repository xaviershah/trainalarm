import Foundation

struct StateCard: Equatable {
    enum Tone { case warn, error, info, plain }
    let tone: Tone
    let title: String
    let detail: String
}

enum TrackingCards {
    /// The cards the tracking screen shows, most urgent first (spec section 6).
    static func make(display d: TrackingDisplay, destination: String) -> [StateCard] {
        var cards: [StateCard] = []
        if d.alarmNotSet {
            cards.append(StateCard(tone: .error, title: "Your alarm is NOT set",
                                   detail: "TrainAlarm could not schedule it and will keep trying. Do not rely on it yet."))
        }
        if d.cancelled {
            cards.append(StateCard(tone: .warn, title: "Train cancelled",
                                   detail: "This train will not run. Your alarm stays set until you tap I'm off."))
        }
        if d.destinationDropped {
            cards.append(StateCard(tone: .error, title: "\(destination) is no longer on this route",
                                   detail: "The train has been diverted. Your alarm stays set. Check with station staff."))
        }
        if d.rateLimited {
            cards.append(StateCard(tone: .plain, title: "Train data is busy", detail: "Too many requests. Retrying shortly."))
        } else if d.feedLost {
            let last = d.lastGoodAt.map { " Last good update \(TimeText.clock($0).plain)." } ?? ""
            cards.append(StateCard(tone: .plain, title: "No live data", detail: "Using the timetable and your GPS.\(last)"))
        }
        if d.isEstimated {
            let how = d.estimateSource == .gpsPace ? "Worked out from your speed and position." : "Worked out from the last live update."
            cards.append(StateCard(tone: .info, title: "Estimated arrival", detail: how))
        }
        return cards
    }
}
