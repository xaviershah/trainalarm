import SwiftUI

struct StationSearchSheet: View {
    @Bindable var model: SessionModel
    let isFrom: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colors) private var c
    @State private var query = ""

    var body: some View {
        let results = model.search(query)
        NavigationStack {
            List(results) { station in
                Button(station.name) {
                    if isFrom { model.selectFrom(station) } else { model.selectTo(station) }
                    dismiss()
                }
                .foregroundStyle(c.head)
                .listRowBackground(c.surface)
            }
            .scrollContentBackground(.hidden)
            .background(c.bg)
            .searchable(text: $query, prompt: isFrom ? "Where are you now?" : "Where are you going?")
            .navigationTitle(isFrom ? "From" : "To")
            .navigationBarTitleDisplayMode(.inline)
            .overlay {
                if query.isEmpty { Text("Type a station name").foregroundStyle(c.muted) }
                else if results.isEmpty { Text("No station called that").foregroundStyle(c.muted) }
            }
        }
        .presentationBackground(c.bg)
    }
}
