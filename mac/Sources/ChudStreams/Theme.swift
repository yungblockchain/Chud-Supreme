import AppKit
import CoreText
import SwiftUI

// CHUD STREAMS for Mac: the same 90s cyberpunk anime look as the Fire TV app.
// Midnight indigo, neon cyan for anything you can click, hot magenta for brand moments,
// panels with two chamfered corners, CRT scanlines and a retro grid floor.

enum Neon {
    static let background = Color(hex: 0x0B0A1F)
    static let backgroundSoft = Color(hex: 0x120F2E)
    static let surface = Color(hex: 0x1B1642)
    static let surfaceRaised = Color(hex: 0x271F5A)
    static let cyan = Color(hex: 0x19F0FF)
    static let cyanPale = Color(hex: 0xAAFAFF)
    static let magenta = Color(hex: 0xFF2BD6)
    static let danger = Color(hex: 0xFF3B6B)
    static let positive = Color(hex: 0x3DFF9A)
    static let onCyan = Color(hex: 0x03141A)
    static let text = Color(hex: 0xEAF6FF)
    static let textSecondary = Color(hex: 0xA9B6E6)
    static let textMuted = Color(hex: 0x6B72A6)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}

enum NeonFont {
    /// Audiowide: wide techno display face for the wordmark, titles and numbers.
    static func display(_ size: CGFloat) -> Font { .custom("Audiowide-Regular", size: size) }

    /// Atkinson Hyperlegible for everything you read.
    static func body(_ size: CGFloat, bold: Bool = false) -> Font {
        .custom(bold ? "AtkinsonHyperlegible-Bold" : "AtkinsonHyperlegible-Regular", size: size)
    }

    /// Registers the fonts shipped inside the .app. Missing files (e.g. `swift run`) fall back to system fonts.
    static func registerBundledFonts() {
        for name in ["audiowide_regular", "atkinson_hyperlegible_regular", "atkinson_hyperlegible_bold"] {
            if let url = Bundle.main.url(forResource: name, withExtension: "ttf") {
                CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
            }
        }
    }
}

/// Two opposite corners cut at 45 degrees, like a heads-up display panel.
struct HudShape: Shape {
    var cut: CGFloat = 10

    func path(in rect: CGRect) -> Path {
        let c = min(cut, rect.width / 2, rect.height / 2)
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + c, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - c))
        path.addLine(to: CGPoint(x: rect.maxX - c, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + c))
        path.closeSubpath()
        return path
    }
}

/// Neon HUD button: cyan outline, fills cyan with a glow on hover (or always, when prominent).
struct NeonButtonStyle: ButtonStyle {
    var prominent = false

    func makeBody(configuration: ButtonStyleConfiguration) -> some View {
        NeonButtonBody(configuration: configuration, prominent: prominent)
    }
}


@MainActor
private struct NeonButtonBody: View {
    let configuration: ButtonStyleConfiguration
    let prominent: Bool
    @State private var hovering = false
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        let lit = (prominent || hovering) && isEnabled
        configuration.label
            .font(NeonFont.body(14, bold: true))
            .foregroundColor(lit ? Neon.onCyan : Neon.text)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(HudShape().fill(lit ? Neon.cyan : Neon.surface))
            .overlay(HudShape().stroke(Neon.cyan.opacity(0.6), lineWidth: 1))
            .shadow(color: Neon.cyan.opacity(hovering && isEnabled ? 0.55 : 0), radius: 10)
            .opacity(isEnabled ? (configuration.isPressed ? 0.8 : 1) : 0.45)
            .contentShape(HudShape())
            .onHover { hovering = $0 }
    }
}

/// Panel background used for cards and lists.
struct NeonPanel: ViewModifier {
    var highlighted = false

    func body(content: Content) -> some View {
        content
            .background(HudShape(cut: 12).fill(highlighted ? Neon.surfaceRaised : Neon.surface.opacity(0.85)))
            .overlay(HudShape(cut: 12).stroke(Neon.cyan.opacity(highlighted ? 0.9 : 0.2), lineWidth: highlighted ? 2 : 1))
            .shadow(color: Neon.cyan.opacity(highlighted ? 0.45 : 0), radius: 12)
    }
}

extension View {
    func neonPanel(highlighted: Bool = false) -> some View { modifier(NeonPanel(highlighted: highlighted)) }
}

/// Night-city backdrop: indigo, a faint magenta grid floor, a horizon glow and CRT scanlines.
@MainActor
struct NeonBackdrop: View {
    var body: some View {
        ZStack {
            Neon.background
            LinearGradient(
                colors: [.clear, Neon.magenta.opacity(0.12)],
                startPoint: UnitPoint(x: 0.5, y: 0.6),
                endPoint: .bottom
            )
            Canvas { context, size in
                let horizon = size.height * 0.7
                let grid = GraphicsContext.Shading.color(Neon.magenta.opacity(0.10))
                let centre = size.width / 2
                for i in -14...14 {
                    var line = Path()
                    line.move(to: CGPoint(x: centre + CGFloat(i) * size.width * 0.02, y: horizon))
                    line.addLine(to: CGPoint(x: centre + CGFloat(i) * size.width * 0.18, y: size.height))
                    context.stroke(line, with: grid, lineWidth: 1)
                }
                for k in 1...8 {
                    let t = CGFloat(k) / 8
                    let y = horizon + (size.height - horizon) * t * t
                    var line = Path()
                    line.move(to: CGPoint(x: 0, y: y))
                    line.addLine(to: CGPoint(x: size.width, y: y))
                    context.stroke(line, with: grid, lineWidth: 1)
                }
                var y: CGFloat = 0
                while y < size.height {
                    context.fill(Path(CGRect(x: 0, y: y, width: size.width, height: 1)), with: .color(.black.opacity(0.16)))
                    y += 3
                }
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}

/// Section heading in the display face.
@MainActor
struct NeonTitle: View {
    let text: String
    var size: CGFloat = 26

    var body: some View {
        ZStack(alignment: .leading) {
            Text(text).font(NeonFont.display(size)).foregroundColor(Neon.magenta).offset(x: 2, y: 1.5)
            Text(text).font(NeonFont.display(size)).foregroundColor(Neon.cyan)
        }
    }
}

enum BrandImage {
    /// The neon logo badge from the app bundle, if present.
    static var badge: NSImage? {
        Bundle.main.url(forResource: "brand_mascot", withExtension: "png").flatMap { NSImage(contentsOf: $0) }
    }
}
