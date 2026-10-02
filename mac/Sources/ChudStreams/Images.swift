import AppKit
import SwiftUI

// Images: channel logos, posters, backdrops and cast photos. Loaded once, kept in memory (up to
// a limit) and on disk, decoded off the main thread. Animated GIF logos play.

/// The in-memory image cache (NSCache is thread-safe).
final class ImageMemory: @unchecked Sendable {
    static let shared = ImageMemory()
    let cache = NSCache<NSURL, NSImage>()

    init() {
        cache.countLimit = 900
        cache.totalCostLimit = 320 << 20
    }
}

actor ImagePipeline {
    static let shared = ImagePipeline()

    private var memory: NSCache<NSURL, NSImage> { ImageMemory.shared.cache }
    private var inFlight: [URL: Task<NSImage?, Never>] = [:]
    private var failed: [URL: Date] = [:]
    private let session: URLSession

    init() {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 20
        configuration.httpMaximumConnectionsPerHost = 6
        let folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CHUD STREAMS/Images", isDirectory: true)
        configuration.urlCache = URLCache(memoryCapacity: 16 << 20, diskCapacity: 400 << 20, directory: folder)
        configuration.requestCachePolicy = .returnCacheDataElseLoad
        session = URLSession(configuration: configuration)
    }

    nonisolated func cached(_ url: URL) -> NSImage? {
        ImageMemory.shared.cache.object(forKey: url as NSURL)
    }

    func image(for url: URL) async -> NSImage? {
        if let hit = memory.object(forKey: url as NSURL) { return hit }
        if let failedAt = failed[url], Date().timeIntervalSince(failedAt) < 300 { return nil }
        if let running = inFlight[url] { return await running.value }
        let session = self.session
        let task = Task<NSImage?, Never>.detached(priority: .utility) {
            var request = URLRequest(url: url)
            request.setValue("image/*", forHTTPHeaderField: "Accept")
            guard let result = try? await session.data(for: request),
                  ((result.1 as? HTTPURLResponse)?.statusCode ?? 200) < 400,
                  let image = NSImage(data: result.0) else { return nil }
            // Decode now, off the main thread, so scrolling never stutters on first draw.
            if !ImagePipeline.isAnimated(image) {
                _ = image.cgImage(forProposedRect: nil, context: nil, hints: nil)
            }
            return image
        }
        inFlight[url] = task
        let result = await task.value
        inFlight[url] = nil
        if let result {
            let cost = Int(result.size.width * result.size.height * 4)
            memory.setObject(result, forKey: url as NSURL, cost: cost)
        } else {
            failed[url] = Date()
        }
        return result
    }

    nonisolated static func isAnimated(_ image: NSImage) -> Bool {
        guard let bitmap = image.representations.first as? NSBitmapImageRep,
              let frames = bitmap.value(forProperty: .frameCount) as? Int else { return false }
        return frames > 1
    }
}

/// A remote image with a neutral placeholder. Animated GIFs play when `animated` is set.
@MainActor
struct RemoteImage: View {
    let url: String?
    let contentMode: ContentMode
    var animated: Bool = false
    @State private var image: NSImage? = nil
    @State private var loadedURL: String? = nil

    var body: some View {
        ZStack {
            if let image {
                if animated && ImagePipeline.isAnimated(image) {
                    AnimatedImage(image: image, fill: contentMode == .fill)
                } else {
                    Image(nsImage: image)
                        .resizable()
                        .interpolation(.high)
                        .aspectRatio(contentMode: contentMode)
                }
            } else {
                Neon.surfaceRaised.opacity(0.7)
                Image(systemName: "sparkles.tv")
                    .foregroundColor(Neon.textMuted)
            }
        }
        .task(id: url) { await load() }
    }

    private func load() async {
        guard let url, let address = URL(string: url), address.scheme != nil else {
            image = nil
            return
        }
        if loadedURL == url, image != nil { return }
        if let cached = ImagePipeline.shared.cached(address) {
            image = cached
            loadedURL = url
            return
        }
        image = nil
        let loaded = await ImagePipeline.shared.image(for: address)
        guard !Task.isCancelled else { return }
        withAnimation(Motion.fade) { image = loaded }
        loadedURL = url
    }
}

/// NSImageView plays animated GIFs.
private struct AnimatedImage: NSViewRepresentable {
    let image: NSImage
    let fill: Bool

    func makeNSView(context: Context) -> NSImageView {
        let view = NSImageView()
        view.animates = true
        view.imageScaling = fill ? .scaleAxesIndependently : .scaleProportionallyUpOrDown
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        view.image = image
        return view
    }

    func updateNSView(_ view: NSImageView, context: Context) {
        if view.image !== image { view.image = image }
    }
}

/// A channel logo that always fits its box: centred, never cropped, on a subtle tile.
@MainActor
struct ChannelLogo: View {
    let url: String?
    var size: CGSize = CGSize(width: 44, height: 44)

    var body: some View {
        RemoteImage(url: url, contentMode: .fit, animated: true)
            .padding(4)
            .frame(width: size.width, height: size.height)
            .background(RoundedRectangle(cornerRadius: 6).fill(Color.white.opacity(0.05)))
            .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}
