import Foundation

public struct MapboxVoiceToolCall: Identifiable, Sendable {
    public let id: UUID
    public var name: String
    public var callId: String
    public var arguments: String
    public var result: String?
    /// The result's MCP `structuredContent` as JSON, when the server sent one.
    public var structuredResult: String?
    public var isError: Bool

    public init(
        id: UUID = UUID(),
        name: String,
        callId: String,
        arguments: String,
        result: String? = nil,
        structuredResult: String? = nil,
        isError: Bool = false
    ) {
        self.id = id
        self.name = name
        self.callId = callId
        self.arguments = arguments
        self.result = result
        self.structuredResult = structuredResult
        self.isError = isError
    }

    public static func prettyJSON(_ text: String) -> String {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let pretty = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]),
              let rendered = String(data: pretty, encoding: .utf8)
        else { return text }
        return rendered
    }
}

/// The tool calls of one voice session, with each result paired to the call that produced it.
public struct MapboxToolLog {
    public private(set) var calls: [MapboxVoiceToolCall] = []

    public init() {}

    /// Records a voice event and returns the places that a successful result lists.
    public mutating func record(_ event: MapboxVoiceEvent) -> [Place] {
        switch event {
        case .toolCall(let name, let callId, let arguments):
            calls.append(MapboxVoiceToolCall(name: name, callId: callId, arguments: arguments))
            return []
        case .toolResult(let name, let callId, let text, let isError, let structuredJSON):
            if let index = calls.lastIndex(where: { $0.callId == callId && $0.result == nil }) {
                calls[index].result = text
                calls[index].structuredResult = structuredJSON
                calls[index].isError = isError
            } else {
                calls.append(MapboxVoiceToolCall(
                    name: name,
                    callId: callId,
                    arguments: "{}",
                    result: text,
                    structuredResult: structuredJSON,
                    isError: isError
                ))
            }
            guard !isError else { return [] }
            let listed = MapboxToolText.places(in: text)
            return listed.isEmpty ? MapboxToolText.places(nearbyIn: structuredJSON) : listed
        case .connecting, .listening, .ended, .failed, .serverError:
            return []
        }
    }

    /// The route a successful `directions_tool` result describes, after `record` has stored it.
    public func route(callId: String) -> RouteRequest? {
        guard let call = calls.last(where: { $0.callId == callId && $0.name == "directions_tool" && !$0.isError }),
              let text = call.result
        else { return nil }
        return MapboxToolText.directions(text: text, arguments: call.arguments)
    }

    public mutating func reset() {
        calls = []
    }
}
