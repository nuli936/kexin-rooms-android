import SwiftUI

@main
struct KexinRoomsApp: App {
    @StateObject private var model = RoomsModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
        }
    }
}
