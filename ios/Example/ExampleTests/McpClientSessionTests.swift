import Foundation
import Testing
@testable import Example

@Suite(.serialized)
struct McpClientSessionTests {
    @Test func anHTTPErrorFailsInsteadOfReturningNoTools() async {
        StubMCP.handler = { _ in
            (401, Data(#"{"message":"Not Authorized"}"#.utf8), [:])
        }
        let session = stubbedSession()

        await #expect(throws: McpClientError.failed(message: "Not Authorized")) {
            try await session.listTools()
        }
    }

    @Test func aBodyWithoutAResultFails() async {
        StubMCP.handler = { request in
            if method(of: request) == "initialize" {
                return (200, Data(#"{"message":"Not Authorized"}"#.utf8), ["Mcp-Session-Id": "sess"])
            }
            return (200, Data(#"{"jsonrpc":"2.0","id":1,"result":{}}"#.utf8), [:])
        }

        await #expect(throws: McpClientError.failed(message: "MCP request failed")) {
            try await stubbedSession().listTools()
        }
    }

    @Test func aFailedCallIsAnErrorInsteadOfAnEmptyResult() async {
        StubMCP.handler = { request in
            switch method(of: request) {
            case "initialize":
                return (200, Data(#"{"jsonrpc":"2.0","id":1,"result":{}}"#.utf8), ["Mcp-Session-Id": "sess"])
            case "notifications/initialized":
                return (202, Data(), [:])
            default:
                return (404, Data(#"{"message":"Not Authorized"}"#.utf8), [:])
            }
        }

        await #expect(throws: McpClientError.failed(message: "Not Authorized")) {
            try await stubbedSession().callTool(name: "search", argumentsJSON: "{}")
        }
    }

    @Test func theEventStreamSkipsOtherRequestsAndJoinsDataLines() async throws {
        let search = """
        {"name":"search","description":"Search places","inputSchema":{"type":"object"}}
        """
        StubMCP.handler = { request in
            switch method(of: request) {
            case "initialize":
                return (200, Data(#"{"jsonrpc":"2.0","id":1,"result":{}}"#.utf8), ["Mcp-Session-Id": "sess"])
            case "notifications/initialized":
                return (202, Data(), [:])
            default:
                let body = """
                data: {"jsonrpc":"2.0","method":"notifications/progress","params":{}}

                data: {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"wrong","description":"","inputSchema":{}}]}}

                data: {"jsonrpc":"2.0","id":3,"result":
                data: {"tools":[\(search)]}}

                """
                return (200, Data(body.utf8), [:])
            }
        }

        let tools = try await stubbedSession().listTools()

        #expect(tools.map(\.name) == ["search"])
        #expect(tools[0].description == "Search places")
    }
}

private func stubbedSession() -> FrameworkMcpSession {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [StubMCP.self]
    return FrameworkMcpSession(
        serverURL: URL(string: "https://mcp.test/mcp")!,
        accessToken: "token",
        urlSession: URLSession(configuration: configuration)
    )
}

private func method(of request: URLRequest) -> String? {
    let data = request.httpBody ?? streamedBody(request)
    let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    return object?["method"] as? String
}

private func streamedBody(_ request: URLRequest) -> Data {
    guard let stream = request.httpBodyStream else { return Data() }
    stream.open()
    defer { stream.close() }
    var data = Data()
    let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: 1024)
    defer { buffer.deallocate() }
    while stream.hasBytesAvailable {
        let count = stream.read(buffer, maxLength: 1024)
        if count <= 0 { break }
        data.append(buffer, count: count)
    }
    return data
}

nonisolated private final class StubMCP: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var handler: ((URLRequest) -> (Int, Data, [String: String]))?

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        let (status, data, headers) = handler(request)
        let response = HTTPURLResponse(
            url: request.url ?? URL(string: "https://mcp.test/mcp")!,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: headers
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
