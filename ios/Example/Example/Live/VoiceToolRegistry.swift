import Foundation

nonisolated struct VoiceToolRegistry: Sendable {
    var tools: [VoiceTool]

    func contains(_ name: String) -> Bool {
        tools.contains { $0.name == name }
    }

    func supportsConcurrentBatch(_ names: [String]) -> Bool {
        guard names.count > 1 else { return false }
        return names.allSatisfy { name in
            guard let tool = tools.first(where: { $0.name == name }) else { return false }
            return tool.supportsConcurrentExecution
        }
    }

    func run(name: String, argumentsJSON: String) async -> VoiceToolOutcome {
        guard let tool = tools.first(where: { $0.name == name }) else {
            return .failure("Tool '\(name)' is not available")
        }
        return await tool.run(argumentsJSON)
    }
}