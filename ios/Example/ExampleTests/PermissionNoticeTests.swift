import Testing
@testable import Example

@Test func startNamesOnlyTheMissingMicrophone() {
    #expect(
        MainViewModel.missingPermissionsText(microphoneGranted: false, locationGranted: true)
            == "Allow microphone access in Settings to start."
    )
}

@Test func startNamesOnlyTheMissingLocation() {
    #expect(
        MainViewModel.missingPermissionsText(microphoneGranted: true, locationGranted: false)
            == "Allow location access in Settings to start."
    )
}

@Test func startNamesBothWhenBothAreMissing() {
    #expect(
        MainViewModel.missingPermissionsText(microphoneGranted: false, locationGranted: false)
            == "Allow microphone and location access in Settings to start."
    )
}

@Test func startHasNoMessageWhenBothAreGranted() {
    #expect(MainViewModel.missingPermissionsText(microphoneGranted: true, locationGranted: true).isEmpty)
}

@Test @MainActor func startStaysTappableWithoutAccessAndOnlyClosingDisablesIt() {
    let model = MainViewModel()

    #expect(model.canToggle)
}
