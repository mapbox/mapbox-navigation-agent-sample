import Foundation

/// Loads the Android live prompts from the app bundle and builds the director's tool list.
nonisolated enum LivePrompts {
    static let endConversationToolName = "end_conversation"

    static let endConversationDescription =
        "Ends the conversation immediately, exactly as if the user pressed the app's stop button. " +
        "Call this as soon as the user signals they are done talking to you — for example \"that's all\", " +
        "\"goodbye\", or \"end the call\" — rather than replying and waiting for confirmation. Don't call " +
        "it when the user only asks you to stop talking, or to stop navigation. " +
        "The conversation closes after the goodbye finishes playing."

    static func liveInstructions(toolNames: [String]) -> String {
        let actions = (toolNames + [endConversationToolName]).joined(separator: ", ")
        return load("LIVE_PROMPT").replacingOccurrences(of: "{{ACTIONS}}", with: actions)
    }

    static func backendInstructions() -> String {
        load("BACKEND_PROMPT")
    }

    /// Registry tools in registry order, then `end_conversation`. Parameter objects have non-integer numbers removed.
    static func tools(for registry: VoiceToolRegistry) -> [VoiceToolSpec] {
        registry.tools.map { tool in
            VoiceToolSpec(
                name: tool.name,
                description: tool.description,
                parameters: withoutFractionalNumbers(tool.parameters)
            )
        } + [endConversationTool]
    }

    private static var endConversationTool: VoiceToolSpec {
        VoiceToolSpec(
            name: endConversationToolName,
            description: endConversationDescription,
            parameters: [
                "type": "object",
                "properties": [String: Any](),
                "additionalProperties": false,
            ]
        )
    }

    /// Android `prompt()` trims the bundled resource. The backend text is otherwise unchanged.
    private static func load(_ name: String) -> String {
        guard let url = promptURL(named: name),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            preconditionFailure("Missing prompt resource: \(name).md")
        }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func promptURL(named name: String) -> URL? {
        let bundle = Bundle.main
        if let url = bundle.url(forResource: name, withExtension: "md") {
            return url
        }
        if let url = bundle.url(forResource: name, withExtension: "md", subdirectory: "Live") {
            return url
        }
        guard let root = bundle.resourceURL,
              let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil) else {
            return nil
        }
        for case let url as URL in enumerator where url.lastPathComponent == "\(name).md" {
            return url
        }
        return nil
    }

    /// Drops object entries and array items whose value is a non-integer JSON number, at every depth.
    private static func withoutFractionalNumbers(_ parameters: [String: Any]) -> [String: Any] {
        sanitized(parameters) as? [String: Any] ?? [:]
    }

    private static func sanitized(_ value: Any) -> Any? {
        if let object = value as? [String: Any] {
            var cleaned: [String: Any] = [:]
            for (key, item) in object {
                if let kept = sanitized(item) {
                    cleaned[key] = kept
                }
            }
            return cleaned
        }
        if let array = value as? [Any] {
            return array.compactMap { sanitized($0) }
        }
        if isFractionalNumber(value) {
            return nil
        }
        return value
    }

    /// JSON booleans are CFBooleans. Integer 0 and 1 also bridge as `Bool`, so the CoreFoundation type is the check.
    /// A JSON number that survived as a float or double is not an integer, including whole values written as `1.0`.
    private static func isFractionalNumber(_ value: Any) -> Bool {
        guard let number = value as? NSNumber else { return false }
        if CFGetTypeID(number) == CFBooleanGetTypeID() { return false }
        switch String(cString: number.objCType) {
        case "d", "f":
            return true
        default:
            return false
        }
    }
}
