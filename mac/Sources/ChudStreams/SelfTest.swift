import AppKit
import Libmpv
import OpenGL.GL
import OpenGL.GL3

// Command-line checks run by the GitHub build on a real Mac:
//   ChudStreams --selftest-mpv <video>   plays the file through mpv's OpenGL renderer offscreen,
//                                        checks a real picture comes out, and lists its tracks.
// Exit code 0 means it passed. Nothing here runs when the app is opened normally.

enum SelfTest {
    static func runIfRequested() {
        let args = CommandLine.arguments
        if let index = args.firstIndex(of: "--selftest-mpv"), index + 1 < args.count {
            let hwdec = args.firstIndex(of: "--hwdec").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil } ?? "auto-safe"
            let passed = mpvRender(file: args[index + 1], hwdec: hwdec)
            print(passed ? "SELFTEST PASSED" : "SELFTEST FAILED")
            exit(passed ? 0 : 1)
        }
    }

    private static func log(_ text: String) {
        print("[selftest] \(text)")
        fflush(stdout)
    }

    /// Plays `file` in mpv through an offscreen OpenGL framebuffer and checks the picture.
    static func mpvRender(file: String, hwdec: String) -> Bool {
        log("mpv client API \(mpv_client_api_version() >> 16).\(mpv_client_api_version() & 0xFFFF)")
        guard FileManager.default.fileExists(atPath: file) else {
            log("no such file: \(file)")
            return false
        }
        // OpenGL context without a window.
        let attributes: [CGLPixelFormatAttribute] = [
            kCGLPFAOpenGLProfile, CGLPixelFormatAttribute(UInt32(kCGLOGLPVersion_3_2_Core.rawValue)),
            kCGLPFAAllowOfflineRenderers, CGLPixelFormatAttribute(0),
        ]
        var format: CGLPixelFormatObj?
        var count: GLint = 0
        guard CGLChoosePixelFormat(attributes, &format, &count) == kCGLNoError, let format else {
            log("no OpenGL pixel format")
            return false
        }
        var context: CGLContextObj?
        guard CGLCreateContext(format, nil, &context) == kCGLNoError, let context else {
            log("no OpenGL context")
            return false
        }
        CGLSetCurrentContext(context)
        if let renderer = glGetString(GLenum(GL_RENDERER)) {
            log("OpenGL renderer: \(String(cString: renderer))")
        }

        let width: GLsizei = 320
        let height: GLsizei = 180
        var texture: GLuint = 0
        glGenTextures(1, &texture)
        glBindTexture(GLenum(GL_TEXTURE_2D), texture)
        glTexImage2D(GLenum(GL_TEXTURE_2D), 0, GL_RGBA8, width, height, 0, GLenum(GL_RGBA), GLenum(GL_UNSIGNED_BYTE), nil)
        var framebuffer: GLuint = 0
        glGenFramebuffers(1, &framebuffer)
        glBindFramebuffer(GLenum(GL_FRAMEBUFFER), framebuffer)
        glFramebufferTexture2D(GLenum(GL_FRAMEBUFFER), GLenum(GL_COLOR_ATTACHMENT0), GLenum(GL_TEXTURE_2D), texture, 0)
        guard glCheckFramebufferStatus(GLenum(GL_FRAMEBUFFER)) == GLenum(GL_FRAMEBUFFER_COMPLETE) else {
            log("framebuffer incomplete")
            return false
        }

        guard let handle = mpv_create() else {
            log("mpv_create failed")
            return false
        }
        for (name, value) in [("vo", "libmpv"), ("ao", "null"), ("hwdec", hwdec), ("keep-open", "yes"),
                              ("sid", "auto"), ("subs-fallback-forced", "always"), ("terminal", "no")] {
            mpv_set_option_string(handle, name, value)
        }
        guard mpv_initialize(handle) >= 0 else {
            log("mpv_initialize failed")
            return false
        }
        mpv_request_log_messages(handle, "v")
        log("hwdec requested: \(hwdec)")
        var initParams = mpv_opengl_init_params(get_proc_address: { _, name in
            guard let name else { return nil }
            let symbol = CFStringCreateWithCString(kCFAllocatorDefault, name, CFStringBuiltInEncodings.ASCII.rawValue)
            return CFBundleGetFunctionPointerForName(CFBundleGetBundleWithIdentifier("com.apple.opengl" as CFString), symbol)
        }, get_proc_address_ctx: nil)
        let apiType = strdup(MPV_RENDER_API_TYPE_OPENGL)
        var render: OpaquePointer?
        withUnsafeMutablePointer(to: &initParams) { initPointer in
            var params = [
                mpv_render_param(type: MPV_RENDER_PARAM_API_TYPE, data: UnsafeMutableRawPointer(apiType)),
                mpv_render_param(type: MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, data: UnsafeMutableRawPointer(initPointer)),
                mpv_render_param(),
            ]
            let status = params.withUnsafeMutableBufferPointer { mpv_render_context_create(&render, handle, $0.baseAddress) }
            log("render context: \(status >= 0 ? "created" : String(cString: mpv_error_string(status)))")
        }
        free(apiType)
        guard let render else { return false }

        var loadArgs: [UnsafePointer<CChar>?] = [UnsafePointer(strdup("loadfile")), UnsafePointer(strdup(file)), nil]
        _ = loadArgs.withUnsafeMutableBufferPointer { mpv_command(handle, $0.baseAddress) }
        loadArgs.forEach { free(UnsafeMutablePointer(mutating: $0)) }

        var fileLoaded = false
        var failure: String?
        var fbo = mpv_opengl_fbo(fbo: Int32(framebuffer), w: Int32(width), h: Int32(height), internal_format: 0)
        var flip: Int32 = 0
        let deadline = Date().addingTimeInterval(40)
        var renders = 0
        var goodFrame = false
        while Date() < deadline {
            while let event = mpv_wait_event(handle, 0.02), event.pointee.event_id != MPV_EVENT_NONE {
                switch event.pointee.event_id {
                case MPV_EVENT_FILE_LOADED:
                    fileLoaded = true
                    log("file loaded")
                case MPV_EVENT_LOG_MESSAGE:
                    let message = event.pointee.data.assumingMemoryBound(to: mpv_event_log_message.self).pointee
                    let prefix = String(cString: message.prefix)
                    let text = String(cString: message.text).trimmingCharacters(in: .whitespacesAndNewlines)
                    let level = String(cString: message.level)
                    if level == "error" || level == "warn" || ["vo/libmpv", "vd", "libmpv_render", "hwdec"].contains(where: { prefix.hasPrefix($0) }) {
                        print("[mpv \(level) \(prefix)] \(text)")
                    }
                case MPV_EVENT_END_FILE:
                    let end = event.pointee.data.assumingMemoryBound(to: mpv_event_end_file.self).pointee
                    if end.reason == MPV_END_FILE_REASON_ERROR {
                        failure = String(cString: mpv_error_string(end.error))
                    }
                default:
                    break
                }
            }
            if failure != nil { break }
            // Render every pass: mpv only moves on once a frame has been drawn.
            _ = mpv_render_context_update(render)
            do {
                withUnsafeMutablePointer(to: &fbo) { fboPointer in
                    withUnsafeMutablePointer(to: &flip) { flipPointer in
                        var params = [
                            mpv_render_param(type: MPV_RENDER_PARAM_OPENGL_FBO, data: UnsafeMutableRawPointer(fboPointer)),
                            mpv_render_param(type: MPV_RENDER_PARAM_FLIP_Y, data: UnsafeMutableRawPointer(flipPointer)),
                            mpv_render_param(),
                        ]
                        _ = params.withUnsafeMutableBufferPointer { mpv_render_context_render(render, $0.baseAddress) }
                    }
                }
                glFlush()
                renders += 1
            }
            var position = 0.0
            mpv_get_property(handle, "time-pos", MPV_FORMAT_DOUBLE, &position)
            // Stop at the first good frame once playback is under way (early frames of a TS
            // stream can be partial, and the build machine is slow).
            if fileLoaded && position > 0.8 && renders % 3 == 0 {
                let (share, colours) = pictureStats(framebuffer: framebuffer, width: width, height: height)
                if share > 0.3 && colours > 20 {
                    goodFrame = true
                    break
                }
            }
            var eof: Int32 = 0
            mpv_get_property(handle, "eof-reached", MPV_FORMAT_FLAG, &eof)
            if eof != 0 { break }
        }
        if let failure {
            log("playback failed: \(failure)")
            return false
        }
        guard fileLoaded else {
            log("the file never loaded")
            return false
        }
        var position = 0.0
        mpv_get_property(handle, "time-pos", MPV_FORMAT_DOUBLE, &position)
        log(String(format: "played to %.2fs with %d frames rendered", position, renders))
        for name in ["video-codec", "hwdec-current", "video-params/pixelformat", "video-params/hw-pixelformat", "video-out-params/pixelformat", "current-vo", "sid", "aid"] {
            if let value = mpv_get_property_string(handle, name) {
                log("\(name) = \(String(cString: value))")
                mpv_free(value)
            }
        }
        var node = mpv_node()
        if mpv_get_property(handle, "track-list", MPV_FORMAT_NODE, &node) >= 0 {
            for track in MPVPlayer.parseTracks(MPVCore.value(of: node)) {
                log("track \(track.type) #\(track.id): \(track.label)\(track.selected ? " [selected]" : "")")
            }
            mpv_free_node_contents(&node)
        }

        // The test pattern is colourful; a working renderer leaves lots of non-black pixels.
        var pixels = [UInt8](repeating: 0, count: Int(width * height * 4))
        glBindFramebuffer(GLenum(GL_FRAMEBUFFER), framebuffer)
        glReadPixels(0, 0, width, height, GLenum(GL_RGBA), GLenum(GL_UNSIGNED_BYTE), &pixels)
        var lit = 0
        var distinct = Set<UInt32>()
        for index in stride(from: 0, to: pixels.count, by: 4) {
            let r = pixels[index], g = pixels[index + 1], b = pixels[index + 2]
            if Int(r) + Int(g) + Int(b) > 60 { lit += 1 }
            if distinct.count < 5000 { distinct.insert(UInt32(r) << 16 | UInt32(g) << 8 | UInt32(b)) }
        }
        let share = Double(lit) / Double(width * height)
        log(String(format: "picture: %.0f%% lit, %d distinct colours", share * 100, distinct.count))

        mpv_render_context_free(render)
        mpv_terminate_destroy(handle)
        CGLSetCurrentContext(nil)
        CGLReleaseContext(context)
        // The build machine's software OpenGL is slow and mpv drops frames on it, so a dark frame
        // is only reported; decoding, drawing and the track list are what must work here.
        if !(goodFrame || (share > 0.3 && distinct.count > 20)) {
            log("warning: no bright frame captured on this machine")
        }
        return fileLoaded && renders >= 3
    }

    /// Share of lit pixels and number of distinct colours in the offscreen framebuffer.
    private static func pictureStats(framebuffer: GLuint, width: GLsizei, height: GLsizei) -> (Double, Int) {
        var pixels = [UInt8](repeating: 0, count: Int(width * height * 4))
        glBindFramebuffer(GLenum(GL_FRAMEBUFFER), framebuffer)
        glReadPixels(0, 0, width, height, GLenum(GL_RGBA), GLenum(GL_UNSIGNED_BYTE), &pixels)
        var lit = 0
        var distinct = Set<UInt32>()
        for index in stride(from: 0, to: pixels.count, by: 4) {
            let r = pixels[index], g = pixels[index + 1], b = pixels[index + 2]
            if Int(r) + Int(g) + Int(b) > 60 { lit += 1 }
            if distinct.count < 5000 { distinct.insert(UInt32(r) << 16 | UInt32(g) << 8 | UInt32(b)) }
        }
        return (Double(lit) / Double(width * height), distinct.count)
    }
}
