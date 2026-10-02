import AppKit
import Darwin
import SwiftUI

// An automated walk through the app for the GitHub build: it signs in to the local test server,
// visits every screen, plays a film and live channels, and saves screenshots plus a summary of
// checks. It only runs when CHUD_TOUR is set (to the folder for the screenshots).

@MainActor
enum TourRunner {
    static var isRunning: Bool { ProcessInfo.processInfo.environment["CHUD_TOUR"] != nil }

    private static var started = false
    private static var output: URL = URL(fileURLWithPath: NSTemporaryDirectory())
    private static var server = "http://127.0.0.1:8080"
    private static var checks: [String: String] = [:]
    private static var failures: [String] = []
    private static var demoFilm: MediaItem? = nil

    static func startIfRequested(model: AppModel) {
        guard let folder = ProcessInfo.processInfo.environment["CHUD_TOUR"], !started else { return }
        started = true
        output = URL(fileURLWithPath: folder, isDirectory: true)
        try? FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
        if let host = ProcessInfo.processInfo.environment["CHUD_TOUR_SERVER"] { server = host }
        Task {
            await run(model)
            finish()
        }
    }

    private static func log(_ text: String) {
        print("[tour] \(text)")
        fflush(stdout)
    }

    private static func check(_ name: String, _ passed: Bool, _ detail: String) {
        checks[name] = (passed ? "PASS " : "FAIL ") + detail
        if !passed { failures.append("\(name): \(detail)") }
        log("\(passed ? "PASS" : "FAIL") \(name): \(detail)")
    }

    private static func wait(_ seconds: Double) async {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    /// Waits until `condition` is true or the time runs out. Returns whether it came true.
    private static func waitFor(_ seconds: Double, _ condition: () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if condition() { return true }
            await wait(0.25)
        }
        return condition()
    }

    private static func go(_ model: AppModel, _ section: AppSection, settle: Double = 2.5) async {
        model.go(section)
        await wait(settle)
    }

    // MARK: The walk

    private static func run(_ model: AppModel) async {
        // GitHub's Macs are virtual machines with no hardware video decoder.
        var settings = PlaybackSettings.current
        settings.hardwareDecoding = false
        settings.renderer = .standard
        settings.save()
        let window = await waitForWindow()
        window?.setFrame(NSRect(x: 40, y: 40, width: 1440, height: 900), display: true)
        await wait(1)
        shot("00-first-run")

        // Sign in to the test server (demo account: rich but small).
        do {
            try await model.addXtream(server: server, username: "demo", password: "demo", name: "Test provider")
            check("sign-in", true, "Xtream sign-in accepted")
        } catch {
            check("sign-in", false, error.localizedDescription)
            return
        }
        guard let catalog = model.catalog else { return }
        let loaded = await waitFor(60) { catalog.hasFullList(.live) && catalog.hasFullList(.movie) && catalog.hasFullList(.series) }
        check("catalogue", loaded, "\(catalog.count(.live)) channels, \(catalog.count(.movie)) films, \(catalog.count(.series)) series")

        // Favourites and a custom group, so those screens have content.
        let channels = catalog.all(.live)
        for item in channels.prefix(6) { model.userData.toggleFavourite(item) }
        let sport = model.userData.createGroup(named: "Sport")
        for item in channels.dropFirst(6).prefix(4) { model.userData.toggle(item, inGroup: sport.id) }
        for item in catalog.all(.movie).prefix(3) { model.userData.toggleLibrary(item) }
        for item in catalog.all(.series).prefix(2) { model.userData.toggleLibrary(item) }

        await go(model, .home, settle: 5)
        shot("01-home")
        await go(model, .live, settle: 4)
        shot("02-live-tv")
        await go(model, .guide, settle: 6)
        shot("03-guide")
        await go(model, .movies, settle: 4)
        shot("04-films")
        await go(model, .series, settle: 3)
        shot("05-series")

        // A film's page (TMDB and Trakt come from the test server).
        demoFilm = catalog.all(.movie).first(where: { $0.name.lowercased().contains("neon") }) ?? catalog.all(.movie).first
        if let film = demoFilm {
            model.open(film)
            await wait(5)
            shot("06-film-details")
            NotificationCenter.default.post(name: DetailsPage.showCast, object: nil)
            await wait(2)
            shot("07-film-details-cast-comments")
            // An actor's page.
            if let tmdbId = try? await TMDB.find(title: film.cleanName, year: film.titleYear, show: false)?.id,
               let details = try? await TMDB.details(id: tmdbId, show: false), let person = details.cast.first {
                withAnimation { model.person = person.id }
                await wait(4)
                shot("08-actor-page")
                model.person = nil
            }
            model.details = nil
            await wait(1)

            // Play it: the built-in player with styled and forced subtitles.
            model.playMovie(film, containerExtension: film.containerExtension, fromStart: true)
            let started = await waitFor(20) { model.playback.player?.loaded == true && (model.playback.player?.position ?? 0) > 0.5 }
            let player = model.playback.player
            check("film-playback", started, "position \(String(format: "%.1f", player?.position ?? 0))s, codec \(player?.videoCodec ?? "?"), hwdec \(player?.hardwareDecoder ?? "?")")
            let subs = player?.subtitleTracks ?? []
            check("subtitle-tracks", subs.count >= 2, subs.map { $0.label }.joined(separator: "; "))
            check("forced-subtitles", player?.selectedSubtitle?.isForced == true, "selected: \(player?.selectedSubtitle?.label ?? "none")")
            await wait(1.5)
            log("OpenGL renderer: \(player?.gl?.rendererName ?? "none") (\(player?.gl?.accelerated == true ? "GPU" : "software"))")
            log("renderer after start: " + MPVGLRenderer.diagnostics)
            await wait(3)
            log("renderer 3 s later: " + MPVGLRenderer.diagnostics)
            check("video-frames-drawn", MPVGLRenderer.framesDrawn > 10, MPVGLRenderer.diagnostics)
            if let video = shot("09-player"), let window {
                let highQuality = pictureLooksLive(video, window: window)
                // The VM's software OpenGL mangles mpv's high-quality upscaling filters (a grid
                // of black lines), while downscaled pictures (mini player, multiview) are clean.
                // Re-check with the plain bilinear scaler to tell that apart from a real fault.
                var plain = false
                if !highQuality {
                    player?.setOption("scale", "bilinear")
                    player?.setOption("cscale", "bilinear")
                    await wait(1.5)
                    if let again = shot("09b-player-bilinear") { plain = pictureLooksLive(again, window: window) }
                    player?.setOption("scale", "lanczos")
                    player?.setOption("cscale", "lanczos")
                }
                let glName = "\(player?.gl?.rendererName ?? "no OpenGL")\(player?.gl?.accelerated == false ? ", simple scaling" : "")"
                check("video-picture", highQuality || plain,
                      (highQuality ? "clean picture" : (plain ? "clean only with the bilinear scaler" : "no clean picture")) + " (\(glName))")
            }
            // Switch subtitles with the S key.
            postKey("s")
            await wait(1)
            shot("10-player-subtitles-toggle")
            // Mini player while browsing.
            model.playback.minimise()
            await go(model, .home, settle: 3)
            shot("11-mini-player-home")
            model.playback.stop()
            await wait(1)
        }

        // A live channel, then multiview with four.
        if channels.count >= 4 {
            model.playLive(channels[0], in: channels)
            let live = await waitFor(20) { model.playback.player?.loaded == true }
            check("live-playback", live, channels[0].name)
            await wait(2)
            shot("12-live-player")
            model.playback.openMultiview()
            for item in channels.dropFirst().prefix(3) { model.playback.addTile(item) }
            let tiles = await waitFor(25) { model.playback.tiles.count == 4 && model.playback.tiles.allSatisfy { $0.player.loaded } }
            check("multiview", tiles, "\(model.playback.tiles.count) tiles, \(model.playback.tiles.filter { $0.player.loaded }.count) playing")
            await wait(3)
            shot("13-multiview")
            model.playback.closeMultiview()
            await wait(1)
        }

        await go(model, .library, settle: 3)
        shot("14-library")
        await go(model, .favorites, settle: 3)
        shot("15-favourites")
        model.pendingSearch = "neon"
        await go(model, .search, settle: 4)
        shot("16-search")
        await go(model, .claude, settle: 2)
        ClaudeChatModel.shared.send("Pick me a film for tonight", app: model)
        let answered = await waitFor(25) { ClaudeChatModel.shared.messages.count >= 2 && !ClaudeChatModel.shared.thinking }
        check("ask-claude", answered, "\(ClaudeChatModel.shared.messages.count) messages")
        await wait(1)
        shot("17-ask-claude")
        await go(model, .markets, settle: 10)
        shot("18-markets")
        await go(model, .games, settle: 2)
        shot("19-arcade")
        await go(model, .settings, settle: 2)
        shot("20-settings")

        // An M3U playlist with an XMLTV guide.
        do {
            try await model.addM3U(url: "\(server)/playlist.m3u", guide: "\(server)/epg.xml.gz", name: "Test playlist")
            if let m3u = model.catalog {
                let ready = await waitFor(30) { m3u.hasFullList(.live) }
                await m3u.loadGuide(force: true)
                let listed = m3u.all(.live).filter { !m3u.xmltvListing(for: $0).isEmpty }.count
                check("m3u", ready && m3u.count(.live) > 0, "\(m3u.count(.live)) channels, \(m3u.count(.movie)) films, guide for \(listed)")
                await go(model, .guide, settle: 5)
                shot("21-m3u-guide")
            }
        } catch {
            check("m3u", false, error.localizedDescription)
        }

        // A provider the size of the viewer's (50k channels, 130k films, 20k series).
        if ProcessInfo.processInfo.environment["CHUD_TOUR_BIG"] != nil {
            let started = Date()
            do {
                try await model.addXtream(server: server, username: "big", password: "big", name: "Big provider")
                if let big = model.catalog {
                    await go(model, .home, settle: 1)
                    shot("22-big-loading")
                    let done = await waitFor(600) { big.hasFullList(.live) && big.hasFullList(.movie) && big.hasFullList(.series) }
                    let seconds = Int(Date().timeIntervalSince(started))
                    check("big-catalogue", done && big.count(.live) >= 49_000 && big.count(.movie) >= 129_000,
                          "\(big.count(.live)) channels, \(big.count(.movie)) films, \(big.count(.series)) series in \(seconds)s, memory \(residentMB()) MB")
                    let searchStart = Date()
                    let results = await big.search("silent harbor", kinds: [.movie], limit: 50)
                    check("big-search", !results.isEmpty, "\(results.count) results in \(Int(Date().timeIntervalSince(searchStart) * 1000)) ms")
                    await go(model, .movies, settle: 4)
                    shot("23-big-films")
                }
            } catch {
                check("big-catalogue", false, error.localizedDescription)
            }
        }
        await tryMetalRenderer(model)
    }

    /// The optional Metal renderer (gpu-next through MoltenVK), last because it's the riskiest.
    private static func tryMetalRenderer(_ model: AppModel) async {
        guard let film = demoFilm else { return }
        var settings = PlaybackSettings.current
        settings.renderer = .advanced
        settings.save()
        model.playMovie(film, containerExtension: film.containerExtension, fromStart: true)
        let playing = await waitFor(25) { model.playback.player?.loaded == true && (model.playback.player?.position ?? 0) > 0.5 }
        check("metal-renderer", playing, "Metal player \(model.playback.player?.usesMetal == true ? "started" : "not used"), position \(String(format: "%.1f", model.playback.player?.position ?? 0))s")
        await wait(1.5)
        shot("24-player-metal")
        model.playback.stop()
        settings.renderer = .standard
        settings.save()
    }

    private static func finish() {
        var summary = "CHUD STREAMS tour\n"
        for key in checks.keys.sorted() { summary += "\(key): \(checks[key] ?? "")\n" }
        summary += "memory: \(residentMB()) MB\n"
        try? summary.write(to: output.appendingPathComponent("summary.txt"), atomically: true, encoding: .utf8)
        log(summary)
        log(failures.isEmpty ? "TOUR PASSED" : "TOUR FAILED: " + failures.joined(separator: " | "))
        exit(failures.isEmpty ? 0 : 1)
    }

    // MARK: Helpers

    private static func waitForWindow() async -> NSWindow? {
        for _ in 0..<40 {
            if let window = NSApp.windows.first(where: { $0.isVisible && $0.contentView != nil }) {
                window.makeKeyAndOrderFront(nil)
                NSApp.activate(ignoringOtherApps: true)
                return window
            }
            await wait(0.25)
        }
        return nil
    }

    private static func postKey(_ characters: String) {
        guard let window = NSApp.keyWindow ?? NSApp.windows.first,
              let event = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                                           windowNumber: window.windowNumber, context: nil, characters: characters,
                                           charactersIgnoringModifiers: characters, isARepeat: false, keyCode: 1) else { return }
        NSApp.postEvent(event, atStart: false)
    }

    /// Saves a PNG of the app window. Uses the window server's picture of our own window (which
    /// includes the video), falling back to drawing the view hierarchy.
    @discardableResult
    private static func shot(_ name: String) -> CGImage? {
        guard let window = NSApp.windows.first(where: { $0.isVisible }) else { return nil }
        var image: CGImage? = windowServerImage(window)
        if image == nil, let view = window.contentView, let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) {
            view.cacheDisplay(in: view.bounds, to: rep)
            image = rep.cgImage
        }
        guard let image else {
            log("could not capture \(name)")
            return nil
        }
        let rep = NSBitmapImageRep(cgImage: image)
        if let data = rep.representation(using: .png, properties: [:]) {
            try? data.write(to: output.appendingPathComponent(name + ".png"))
            log("screenshot \(name)")
        }
        return image
    }

    private typealias WindowImageFunction = @convention(c) (CGRect, UInt32, UInt32, UInt32) -> Unmanaged<CGImage>?

    /// CGWindowListCreateImage, looked up at run time (newer SDKs no longer declare it).
    private static func windowServerImage(_ window: NSWindow) -> CGImage? {
        guard let symbol = dlsym(UnsafeMutableRawPointer(bitPattern: -2), "CGWindowListCreateImage") else { return nil }
        let function = unsafeBitCast(symbol, to: WindowImageFunction.self)
        // .null rect, .optionIncludingWindow (8), .boundsIgnoreFraming (1) | .bestResolution (8)
        let unmanaged = function(CGRect.null, 8, UInt32(window.windowNumber), 1 | 8)
        return unmanaged?.takeRetainedValue()
    }

    /// True if the middle of the picture has plenty of varied, bright pixels (a playing video).
    private static func pictureLooksLive(_ image: CGImage, window: NSWindow) -> Bool {
        let width = image.width
        let height = image.height
        guard width > 100, height > 100,
              let context = CGContext(data: nil, width: 64, height: 36, bitsPerComponent: 8, bytesPerRow: 64 * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
              let crop = image.cropping(to: CGRect(x: width / 4, y: height / 4, width: width / 2, height: height / 2)) else { return false }
        context.draw(crop, in: CGRect(x: 0, y: 0, width: 64, height: 36))
        guard let data = context.data else { return false }
        let pixels = data.bindMemory(to: UInt8.self, capacity: 64 * 36 * 4)
        var lit = 0
        var colours = Set<Int>()
        for index in 0..<(64 * 36) {
            let r = Int(pixels[index * 4]), g = Int(pixels[index * 4 + 1]), b = Int(pixels[index * 4 + 2])
            if r + g + b > 90 { lit += 1 }
            colours.insert((r >> 4) << 8 | (g >> 4) << 4 | (b >> 4))
        }
        log("video area: \(lit * 100 / (64 * 36))% lit, \(colours.count) colours")
        return lit > 64 * 36 / 3 && colours.count > 12
    }

    private static func residentMB() -> Int {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size) / 4
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? Int(info.resident_size >> 20) : -1
    }
}
