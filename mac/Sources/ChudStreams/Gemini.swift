import Foundation
import SwiftUI

// Gemini, baked in. Questions use whatever is playing and, for live TV, the programme
// the guide says is on now. Recommendations stay on this page, separate from Ask Claude.

@MainActor
struct GeminiView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var playback: PlaybackCenter
    @State private var draft = ""
    @State private var lines: [GeminiLine] = []
    @State private var working = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            NeonTitle(text: "Gemini", size: 34)
            Text(contextLine)
                .font(NeonFont.body(14))
                .foregroundColor(Neon.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                Button("Recommend something") { ask(recommendPrompt) }
                    .buttonStyle(NeonButtonStyle())
                    .disabled(working)
                Button("What's related to this?") { ask(relatedPrompt) }
                    .buttonStyle(NeonButtonStyle())
                    .disabled(working)
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    ForEach(lines) { line in
                        Text(line.text)
                            .font(NeonFont.body(14))
                            .foregroundColor(line.mine ? Neon.text : Neon.cyan)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .neonPanel(highlighted: !line.mine)
                    }
                }
            }
            HStack(spacing: 10) {
                TextField("Ask about what you're watching", text: $draft)
                    .textFieldStyle(.plain)
                    .font(NeonFont.body(15))
                    .padding(10)
                    .neonPanel()
                    .onSubmit { ask(draft) }
                Button(working ? "Thinking…" : "Ask") { ask(draft) }
                    .buttonStyle(NeonButtonStyle(prominent: true))
                    .disabled(working || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var contextLine: String {
        let now = watching
        if now.isEmpty { return "Nothing is playing. Ask anyway, or start a channel and Gemini will use the guide." }
        return "Using what's on now: \(now)"
    }

    private var watching: String {
        var parts: [String] = []
        if let current = playback.current {
            parts.append(current.title)
            if let subtitle = current.subtitle, !subtitle.isEmpty { parts.append(subtitle) }
            if current.live, let item = current.item, let programmes = model.epg[item.id] {
                if let on = programmes.first(where: { $0.isOn(at: Date()) }) {
                    parts.append("guide says \(on.title)")
                    if !on.description.isEmpty { parts.append(on.description) }
                }
            }
        }
        return parts.joined(separator: ". ")
    }

    private var recommendPrompt: String {
        "Recommend three things to watch next based on this, and say why in one line each. If a live programme is named, stay close to that subject. \(watching)"
    }

    private var relatedPrompt: String {
        "Pull in other relevant facts about what is on: the teams, the film, the cast, or the story, using only what this context supports. Say when you are unsure. Context: \(watching)"
    }

    private func ask(_ text: String) {
        let question = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !question.isEmpty, !working else { return }
        draft = ""
        lines.append(GeminiLine(mine: true, text: question))
        working = true
        let history = lines
        let context = watching
        Task {
            let reply = await GeminiClient.reply(history: history, context: context)
            lines.append(GeminiLine(mine: false, text: reply))
            working = false
        }
    }
}

struct GeminiLine: Identifiable {
    let id = UUID()
    var mine: Bool
    var text: String
}

enum GeminiClient {
    static func reply(history: [GeminiLine], context: String) async -> String {
        guard let key = Secrets.get(.gemini) else { return "No Gemini key is saved." }
        let system = "You are inside a television app. Be brief. The viewer is watching: \(context.isEmpty ? "nothing right now" : context). Do not invent scores or plot points you were not given."
        let contents: [[String: Any]] = history.suffix(12).map { line in
            ["role": line.mine ? "user" : "model", "parts": [["text": line.text]]]
        }
        let models = ["gemini-2.5-flash", "gemini-flash-latest", "gemini-2.0-flash"]
        var last = "Gemini didn't answer."
        for model in models {
            last = await once(model: model, key: key, contents: contents, system: system)
            let lower = last.lowercased()
            if lower.contains("not found") || lower.contains("not supported") || lower.contains("is not found") { continue }
            return last
        }
        return last
    }

    private static func once(model: String, key: String, contents: [[String: Any]], system: String) async -> String {
        guard let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/\(model):generateContent?key=\(key)") else {
            return "Couldn't build the Gemini request."
        }
        let body: [String: Any] = [
            "systemInstruction": ["parts": [["text": system]]],
            "contents": contents,
        ]
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 40
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return "Gemini sent something that wasn't readable (\(code))."
            }
            if let error = (root["error"] as? [String: Any])?["message"] as? String {
                return error
            }
            let candidates = root["candidates"] as? [[String: Any]]
            let parts = (candidates?.first?["content"] as? [String: Any])?["parts"] as? [[String: Any]]
            let text = parts?.compactMap { $0["text"] as? String }.joined(separator: "\n") ?? ""
            return text.isEmpty ? "Gemini didn't answer (\(code))." : text
        } catch {
            return "Couldn't reach Gemini. Check your connection."
        }
    }
}
