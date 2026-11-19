import SwiftUI

/// The voice button under the map. It is full width and shows the session's phase, as the Android sample's does.
struct VoiceControl: View {
    @ObservedObject var voice: MainViewModel
    @State private var showToolLog = false

    var body: some View {
        Button(action: voice.toggle) {
            HStack(alignment: .center) {
                Image(systemName: "microphone")
                Text(title)
                    .font(.headline)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
        }
        .buttonStyle(.borderedProminent)
        .tint(voice.phase == .listening ? .red : .accentColor)
        .disabled(!voice.canToggle)
        .padding(8)
        .toolbar {
            #if DEBUG
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showToolLog = true
                } label: {
                    Label("Tool calls", systemImage: "list.bullet.rectangle")
                }
                .labelStyle(.iconOnly)
                .badge(voice.log.calls.count)
                .disabled(voice.log.calls.isEmpty)
            }
            #endif
        }
        #if DEBUG
        .sheet(isPresented: $showToolLog) {
            toolLog
        }
        #endif
    }

    private var title: String {
        switch voice.phase {
        case .idle: "Start"
        case .connecting: "Connecting…"
        case .listening: "Listening"
        case .closing: "Stopping…"
        }
    }

    #if DEBUG
    private var toolLog: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 16) {
                        ForEach(Array(voice.log.calls.enumerated()), id: \.element.id) { index, call in
                            toolCallRow(index: index + 1, call: call)
                                .id(call.id)
                        }
                    }
                    .padding(16)
                }
                .onChange(of: voice.log.calls.map { "\($0.id)-\($0.result?.count ?? 0)" }) { _, _ in
                    guard let id = voice.log.calls.last?.id else { return }
                    withAnimation { proxy.scrollTo(id, anchor: .bottom) }
                }
            }
            .navigationTitle("Tool calls")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { showToolLog = false }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func toolCallRow(index: Int, call: MapboxVoiceToolCall) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Text("\(index)  \(call.name)")
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                Spacer(minLength: 8)
                if call.result == nil {
                    ProgressView().controlSize(.mini)
                } else if call.isError {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
            Text(MapboxVoiceToolCall.prettyJSON(call.arguments))
                .font(.caption2.monospaced())
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let result = call.result {
                Text(result)
                    .font(.caption2.monospaced())
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
    #endif
}

/// A short banner at the top of the map, like the Android sample's snackbar. It hides after `notice.seconds`.
struct NoticeBanner: View {
    let notice: MainViewModel.Notice
    let dismiss: (UUID) -> Void

    var body: some View {
        Text(notice.text)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(notice.isError ? Color.red : Color.primary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.thickMaterial, in: Capsule())
            .padding(.horizontal, 24)
            .padding(.top, 8)
            .task(id: notice.id) {
                try? await Task.sleep(for: .seconds(notice.seconds))
                dismiss(notice.id)
            }
    }
}
