import CoreGraphics
import Testing
@testable import Example

@Test func ornamentsClearTheVoiceControl() {
    // iPhone safe area is 34pt, and the browsing camera padding is 96pt from the view edge.
    // The old margin (96 - 48 = 48) sat inside a voice control about 66pt tall.
    let margin = ornamentBottomMargin(chromeBottom: 96, safeAreaBottom: 34, voiceControlHeight: 66)

    #expect(margin == 74)
    #expect(margin > 66)
}

@Test func ornamentsUseTheCameraPaddingBeforeTheVoiceControlIsMeasured() {
    let margin = ornamentBottomMargin(chromeBottom: 96, safeAreaBottom: 34, voiceControlHeight: 0)

    #expect(margin == 70)
}

@Test func ornamentsClearATallerVoiceControl() {
    let margin = ornamentBottomMargin(chromeBottom: 96, safeAreaBottom: 34, voiceControlHeight: 120)

    #expect(margin == 128)
}

@Test func ornamentsClearThePlaceCard() {
    let margin = ornamentBottomMargin(chromeBottom: 360, safeAreaBottom: 34, voiceControlHeight: 66)

    #expect(margin == 334)
}

@Test func ornamentsClearTheGuidanceBar() {
    let margin = ornamentBottomMargin(chromeBottom: 180, safeAreaBottom: 34, voiceControlHeight: 66)

    #expect(margin == 154)
}
