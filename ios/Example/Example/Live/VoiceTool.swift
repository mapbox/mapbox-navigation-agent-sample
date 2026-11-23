import Foundation

nonisolated struct VoiceToolOutcome: Equatable, Sendable {
    var output: String
    var error: String?

    static func failure(_ message: String) -> VoiceToolOutcome {
        VoiceToolOutcome(output: message, error: message)
    }
}

nonisolated struct VoiceTool: Sendable {
    var name: String
    var description: String
    private let parametersJSON: String
    var supportsConcurrentExecution: Bool
    var run: @Sendable (String) async -> VoiceToolOutcome

    var parameters: [String: Any] {
        let data = Data(parametersJSON.utf8)
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    init(
        name: String,
        description: String,
        parameters: [String: Any],
        supportsConcurrentExecution: Bool,
        run: @escaping @Sendable (String) async -> VoiceToolOutcome
    ) {
        self.name = name
        self.description = description
        let data = (try? JSONSerialization.data(withJSONObject: parameters)) ?? Data("{}".utf8)
        self.parametersJSON = String(decoding: data, as: UTF8.self)
        self.supportsConcurrentExecution = supportsConcurrentExecution
        self.run = run
    }
}