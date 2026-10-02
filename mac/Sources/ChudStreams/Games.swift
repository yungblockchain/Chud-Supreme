import AppKit
import Foundation
import SwiftUI

// Arcade: the three mini-games from the Fire TV app (Snake, Blocks and Sky Hop), played with the
// keyboard. Same rules, speeds, scoring and neon look as app/tv/.../MiniGames.kt. Every helper type
// is private to this file and prefixed with Arcade; GamesView is the only entry point.

// MARK: - Shared model

private enum ArcadeGame: String, CaseIterable, Identifiable {
    case snake
    case blocks
    case skyhop

    var id: String { rawValue }

    var title: String {
        switch self {
        case .snake: return "Snake"
        case .blocks: return "Blocks"
        case .skyhop: return "Sky Hop"
        }
    }

    var blurb: String {
        switch self {
        case .snake: return "Steer, eat, grow. Don't hit the walls or yourself."
        case .blocks: return "Fit the falling pieces together to clear full rows."
        case .skyhop: return "Bounce from ledge to ledge and climb as high as you can."
        }
    }

    /// One-line controls hint for the menu card.
    var hint: String {
        switch self {
        case .snake: return "Arrows or WASD steer"
        case .blocks: return "Arrows move and turn, Space drops"
        case .skyhop: return "Hold left or right to steer"
        }
    }

    /// Full controls list for the side panel.
    var controls: [ArcadeControlHint] {
        switch self {
        case .snake:
            return [
                ArcadeControlHint(keys: "Arrows / WASD", action: "Steer"),
                ArcadeControlHint(keys: "Space / P", action: "Pause"),
                ArcadeControlHint(keys: "Esc", action: "Back to menu"),
            ]
        case .blocks:
            return [
                ArcadeControlHint(keys: "← → / A D", action: "Move"),
                ArcadeControlHint(keys: "↑ / W", action: "Turn"),
                ArcadeControlHint(keys: "↓ / S", action: "Drop faster"),
                ArcadeControlHint(keys: "Space", action: "Drop at once"),
                ArcadeControlHint(keys: "P", action: "Pause"),
                ArcadeControlHint(keys: "Esc", action: "Back to menu"),
            ]
        case .skyhop:
            return [
                ArcadeControlHint(keys: "← → / A D", action: "Hold to steer"),
                ArcadeControlHint(keys: "Edges", action: "Wrap to the other side"),
                ArcadeControlHint(keys: "Space / P", action: "Pause"),
                ArcadeControlHint(keys: "Esc", action: "Back to menu"),
            ]
        }
    }

    var defaultsKey: String { "arcade.best." + rawValue }

    /// Width over height of the play area.
    var aspect: CGFloat {
        switch self {
        case .snake: return 28.0 / 16.0
        case .blocks: return 16.0 / 20.0
        case .skyhop: return 100.0 / 160.0
        }
    }
}

private enum ArcadePhase {
    case ready
    case playing
    case paused
    case over
}

private struct ArcadeControlHint: Hashable {
    let keys: String
    let action: String
}

private enum ArcadeKey {
    case up
    case down
    case left
    case right
    case confirm
    case space
    case pause
    case escape
    case other

    /// Hardware key codes, so WASD sits in the same place on every keyboard layout.
    init(keyCode: UInt16) {
        switch keyCode {
        case 126, 13: self = .up // Up arrow, W
        case 125, 1: self = .down // Down arrow, S
        case 123, 0: self = .left // Left arrow, A
        case 124, 2: self = .right // Right arrow, D
        case 36, 76: self = .confirm // Return, keypad Enter
        case 49: self = .space
        case 35: self = .pause // P
        case 53: self = .escape
        default: self = .other
        }
    }
}

private enum ArcadePalette {
    static let pieces: [Color] = [
        Color(hex: 0x19F0FF), // cyan
        Color(hex: 0xFFE14D), // yellow
        Color(hex: 0xFF2BD6), // magenta
        Color(hex: 0x3DFF9A), // green
        Color(hex: 0xFF3B6B), // red
        Color(hex: 0x4D7CFF), // blue
        Color(hex: 0xFF9A3D), // orange
    ]

    static func piece(_ index: Int) -> Color {
        if index >= 0 && index < pieces.count {
            return pieces[index]
        }
        return Neon.cyan
    }
}

private struct ArcadeCell: Hashable {
    var x: Int
    var y: Int
}

// MARK: - Snake

private enum ArcadeHeading {
    case up
    case down
    case left
    case right

    var dx: Int {
        switch self {
        case .left: return -1
        case .right: return 1
        case .up, .down: return 0
        }
    }

    var dy: Int {
        switch self {
        case .up: return -1
        case .down: return 1
        case .left, .right: return 0
        }
    }

    func isOpposite(_ other: ArcadeHeading) -> Bool {
        return dx == -other.dx && dy == -other.dy
    }
}

private final class ArcadeSnakeEngine {
    let cols: Int = 28
    let rows: Int = 16
    /// Head first.
    private(set) var body: [ArcadeCell] = []
    private(set) var food = ArcadeCell(x: 0, y: 0)
    private(set) var score: Int = 0
    private(set) var alive: Bool = true
    private var heading: ArcadeHeading = .right
    /// Up to two buffered turns, so a quick "up then left" both land.
    private var turns: [ArcadeHeading] = []
    private var timer: Double = 0

    init() {
        reset()
    }

    func reset() {
        var start: [ArcadeCell] = []
        for i in 0..<4 {
            start.append(ArcadeCell(x: 7 - i, y: rows / 2))
        }
        body = start
        heading = .right
        turns = []
        score = 0
        timer = 0
        alive = true
        placeFood()
    }

    func start() {
        reset()
    }

    func turn(_ next: ArcadeHeading) {
        let last: ArcadeHeading = turns.last ?? heading
        if next == last || next.isOpposite(last) {
            return
        }
        if turns.count < 2 {
            turns.append(next)
        }
    }

    func step(_ dt: Double) {
        guard alive else { return }
        timer += dt
        // Speeds up a little with every snack.
        let interval: Double = max(0.06, 0.14 - Double(score) * 0.0015)
        while timer >= interval && alive {
            timer -= interval
            advance()
        }
    }

    private func advance() {
        if !turns.isEmpty {
            heading = turns.removeFirst()
        }
        guard let head = body.first else {
            alive = false
            return
        }
        let next = ArcadeCell(x: head.x + heading.dx, y: head.y + heading.dy)
        let grows: Bool = next == food
        let hitsWall: Bool = next.x < 0 || next.x >= cols || next.y < 0 || next.y >= rows
        // The tail moves out of the way this step unless the snake is growing.
        var blocked: [ArcadeCell] = body
        if !grows && !blocked.isEmpty {
            blocked.removeLast()
        }
        if hitsWall || blocked.contains(next) {
            alive = false
            return
        }
        body.insert(next, at: 0)
        if grows {
            score += 10
            placeFood()
        } else {
            body.removeLast()
        }
    }

    private func placeFood() {
        let taken = Set(body)
        var free: [ArcadeCell] = []
        for x in 0..<cols {
            for y in 0..<rows {
                let cell = ArcadeCell(x: x, y: y)
                if !taken.contains(cell) {
                    free.append(cell)
                }
            }
        }
        if let pick = free.randomElement() {
            food = pick
        } else {
            alive = false
        }
    }
}

// MARK: - Blocks (falling-blocks puzzle)

private final class ArcadeBlocksEngine {
    static let cols: Int = 10
    static let rows: Int = 20
    static let squarePiece: Int = 1
    static let lineScores: [Int] = [0, 100, 300, 500, 800]

    /// The seven four-square pieces, as cells around a pivot at (0, 0). y grows downwards.
    static let pieces: [[ArcadeCell]] = [
        ArcadeBlocksEngine.shape([-1, 0, 0, 0, 1, 0, 2, 0]),
        ArcadeBlocksEngine.shape([0, 0, 1, 0, 0, 1, 1, 1]),
        ArcadeBlocksEngine.shape([-1, 0, 0, 0, 1, 0, 0, -1]),
        ArcadeBlocksEngine.shape([-1, 0, 0, 0, 0, -1, 1, -1]),
        ArcadeBlocksEngine.shape([-1, -1, 0, -1, 0, 0, 1, 0]),
        ArcadeBlocksEngine.shape([-1, -1, -1, 0, 0, 0, 1, 0]),
        ArcadeBlocksEngine.shape([1, -1, -1, 0, 0, 0, 1, 0]),
    ]

    static func shape(_ coords: [Int]) -> [ArcadeCell] {
        var result: [ArcadeCell] = []
        var i = 0
        while i + 1 < coords.count {
            result.append(ArcadeCell(x: coords[i], y: coords[i + 1]))
            i += 2
        }
        return result
    }

    static func randomKind() -> Int {
        return Int.random(in: 0..<pieces.count)
    }

    /// 0 is empty; otherwise the colour index plus one. Indexed [row][column].
    private(set) var board: [[Int]] = []
    private(set) var cells: [ArcadeCell] = []
    private(set) var kind: Int = 0
    private(set) var nextKind: Int = 0
    private(set) var px: Int = 4
    private(set) var py: Int = 1
    private(set) var score: Int = 0
    private(set) var level: Int = 1
    private(set) var lines: Int = 0
    private(set) var alive: Bool = true
    private var fall: Double = 0

    init() {
        reset()
    }

    func reset() {
        let emptyRow: [Int] = Array(repeating: 0, count: ArcadeBlocksEngine.cols)
        board = Array(repeating: emptyRow, count: ArcadeBlocksEngine.rows)
        kind = 0
        cells = ArcadeBlocksEngine.pieces[0]
        nextKind = ArcadeBlocksEngine.randomKind()
        px = 4
        py = 1
        score = 0
        level = 1
        lines = 0
        fall = 0
        alive = true
    }

    func start() {
        reset()
        spawn()
    }

    func fits(_ shape: [ArcadeCell], x: Int, y: Int) -> Bool {
        for c in shape {
            let cx = x + c.x
            let cy = y + c.y
            if cx < 0 || cx >= ArcadeBlocksEngine.cols || cy >= ArcadeBlocksEngine.rows {
                return false
            }
            if cy >= 0 && board[cy][cx] != 0 {
                return false
            }
        }
        return true
    }

    func move(_ dx: Int) {
        guard alive else { return }
        if fits(cells, x: px + dx, y: py) {
            px += dx
        }
    }

    func rotate() {
        guard alive, kind != ArcadeBlocksEngine.squarePiece else { return }
        let turned: [ArcadeCell] = cells.map { ArcadeCell(x: -$0.y, y: $0.x) }
        // Nudge sideways if the turn would clip a wall or another block.
        let kicks: [Int] = [0, -1, 1, -2, 2]
        for kick in kicks {
            if fits(turned, x: px + kick, y: py) {
                cells = turned
                px += kick
                return
            }
        }
    }

    func softDrop() {
        guard alive else { return }
        if fits(cells, x: px, y: py + 1) {
            py += 1
            score += 1
        } else {
            lock()
        }
    }

    func hardDrop() {
        guard alive else { return }
        var dropped = 0
        while fits(cells, x: px, y: py + 1) {
            py += 1
            dropped += 1
        }
        score += dropped * 2
        lock()
    }

    func ghostY() -> Int {
        var gy = py
        while fits(cells, x: px, y: gy + 1) {
            gy += 1
        }
        return gy
    }

    func step(_ dt: Double) {
        guard alive else { return }
        fall += dt
        let interval: Double = max(0.08, 0.8 - Double(level - 1) * 0.07)
        if fall >= interval {
            fall = 0
            if fits(cells, x: px, y: py + 1) {
                py += 1
            } else {
                lock()
            }
        }
    }

    private func spawn() {
        kind = nextKind
        nextKind = ArcadeBlocksEngine.randomKind()
        cells = ArcadeBlocksEngine.pieces[kind]
        px = 4
        py = 1
        if !fits(cells, x: px, y: py) {
            alive = false
        }
    }

    private func lock() {
        for c in cells {
            let x = px + c.x
            let y = py + c.y
            if y < 0 {
                alive = false
                return
            }
            board[y][x] = kind + 1
        }
        var cleared = 0
        var row = ArcadeBlocksEngine.rows - 1
        while row >= 0 {
            if board[row].allSatisfy({ $0 != 0 }) {
                // Rows above shift down into this index, so check the same index again.
                board.remove(at: row)
                board.insert(Array(repeating: 0, count: ArcadeBlocksEngine.cols), at: 0)
                cleared += 1
            } else {
                row -= 1
            }
        }
        let scoreIndex: Int = min(cleared, ArcadeBlocksEngine.lineScores.count - 1)
        score += ArcadeBlocksEngine.lineScores[scoreIndex] * level
        lines += cleared
        level = 1 + lines / 10
        fall = 0
        spawn()
    }
}

// MARK: - Sky Hop (endless vertical jumper)

private struct ArcadeHopPlatform {
    var x: Double
    var y: Double
    var moving: Bool
    var direction: Double
}

private final class ArcadeHopEngine {
    static let worldW: Double = 100
    static let worldH: Double = 160
    static let gravity: Double = -220
    static let jumpSpeed: Double = 150
    static let runSpeed: Double = 72
    static let platformW: Double = 18
    static let hopperSize: Double = 8

    private(set) var x: Double = 50
    private(set) var y: Double = 6
    private(set) var vx: Double = 0
    private(set) var vy: Double = 0
    private(set) var camera: Double = 0
    private(set) var facingRight: Bool = true
    private(set) var platforms: [ArcadeHopPlatform] = []
    private(set) var score: Int = 0
    private(set) var alive: Bool = true
    private var holdLeft: Bool = false
    private var holdRight: Bool = false
    private var highest: Double = 0

    init() {
        reset()
    }

    /// Puts the hopper on the starting ledge, standing still. Held keys are kept.
    func reset() {
        let w: Double = ArcadeHopEngine.worldW
        let pw: Double = ArcadeHopEngine.platformW
        platforms = [ArcadeHopPlatform(x: w / 2 - pw / 2, y: 6, moving: false, direction: 0)]
        highest = 6
        x = w / 2
        y = 6
        vx = 0
        vy = 0
        camera = 0
        score = 0
        alive = true
        facingRight = true
        addPlatforms()
    }

    func start() {
        reset()
        y = 8
        vy = ArcadeHopEngine.jumpSpeed
    }

    func press(_ key: ArcadeKey) {
        if key == .left { holdLeft = true }
        if key == .right { holdRight = true }
    }

    func release(_ key: ArcadeKey) {
        if key == .left { holdLeft = false }
        if key == .right { holdRight = false }
    }

    func releaseKeys() {
        holdLeft = false
        holdRight = false
    }

    func step(_ dt: Double) {
        guard alive else { return }
        let w: Double = ArcadeHopEngine.worldW
        let pw: Double = ArcadeHopEngine.platformW
        let run: Double = ArcadeHopEngine.runSpeed

        var target: Double = 0
        if holdLeft && !holdRight { target = -run }
        if holdRight && !holdLeft { target = run }
        let blend: Double = min(1, dt * 10)
        vx += (target - vx) * blend
        if vx > 1 {
            facingRight = true
        } else if vx < -1 {
            facingRight = false
        }
        x += vx * dt
        // Leave one side of the screen and come back on the other.
        if x < 0 { x += w }
        if x >= w { x -= w }

        for i in platforms.indices where platforms[i].moving {
            platforms[i].x += platforms[i].direction * 22 * dt
            if platforms[i].x < 0 {
                platforms[i].x = 0
                platforms[i].direction = 1
            } else if platforms[i].x > w - pw {
                platforms[i].x = w - pw
                platforms[i].direction = -1
            }
        }

        let previousY: Double = y
        vy += ArcadeHopEngine.gravity * dt
        y += vy * dt
        if vy < 0 {
            let reach: Double = pw / 2 + ArcadeHopEngine.hopperSize / 2
            for p in platforms {
                let centre: Double = p.x + pw / 2
                if previousY >= p.y && y <= p.y && abs(x - centre) < reach {
                    y = p.y
                    vy = ArcadeHopEngine.jumpSpeed
                    break
                }
            }
        }
        score = max(score, Int(y / 2))
        camera = max(camera, y - ArcadeHopEngine.worldH * 0.45)
        let cutoff: Double = camera - 10
        platforms.removeAll { $0.y < cutoff }
        addPlatforms()
        if y < camera - 12 {
            alive = false
        }
    }

    /// Keeps the screen above the player filled; gaps widen and platforms start moving as you climb.
    private func addPlatforms() {
        let w: Double = ArcadeHopEngine.worldW
        let pw: Double = ArcadeHopEngine.platformW
        while highest < camera + ArcadeHopEngine.worldH + 30 {
            let difficulty: Double = min(1, camera / 2500)
            let gap: Double = 10 + difficulty * 24 + Double.random(in: 0..<1) * 12
            highest += gap
            let chance: Double = 0.35 * difficulty + 0.05
            let moving: Bool = camera > 250 && Double.random(in: 0..<1) < chance
            let left: Double = Double.random(in: 0..<1) * (w - pw)
            let direction: Double = Bool.random() ? 1 : -1
            platforms.append(ArcadeHopPlatform(x: left, y: highest, moving: moving, direction: direction))
        }
    }
}

// MARK: - Controller

@MainActor
private final class ArcadeController: ObservableObject {
    @Published var selected: ArcadeGame = .snake
    @Published private(set) var active: ArcadeGame? = nil
    @Published private(set) var phase: ArcadePhase = .ready
    @Published private(set) var best: [ArcadeGame: Int] = [:]
    @Published private(set) var newRecord: Bool = false

    let snake = ArcadeSnakeEngine()
    let blocks = ArcadeBlocksEngine()
    let hop = ArcadeHopEngine()

    private var lastFrame: Date? = nil
    private var overAt: Date = .distantPast

    init() {
        var loaded: [ArcadeGame: Int] = [:]
        for game in ArcadeGame.allCases {
            loaded[game] = UserDefaults.standard.integer(forKey: game.defaultsKey)
        }
        best = loaded
    }

    func bestScore(_ game: ArcadeGame) -> Int {
        return best[game] ?? 0
    }

    func score(for game: ArcadeGame) -> Int {
        switch game {
        case .snake: return snake.score
        case .blocks: return blocks.score
        case .skyhop: return hop.score
        }
    }

    var currentScore: Int {
        guard let game = active else { return 0 }
        return score(for: game)
    }

    /// Text for the box in the middle of the play area, or nil while playing.
    var message: String? {
        guard let game = active else { return nil }
        switch phase {
        case .ready:
            return "Press Space or Return to start"
        case .playing:
            return nil
        case .paused:
            return "Paused\nPress P or Space to resume"
        case .over:
            let points: Int = score(for: game)
            var text: String = "Game over\nScore " + String(points)
            if newRecord {
                text += "\nNew best!"
            } else {
                text += "\nBest " + String(bestScore(game))
            }
            text += "\nSpace to play again"
            return text
        }
    }

    // MARK: Flow

    func open(_ game: ArcadeGame) {
        selected = game
        hop.releaseKeys()
        switch game {
        case .snake: snake.reset()
        case .blocks: blocks.reset()
        case .skyhop: hop.reset()
        }
        newRecord = false
        lastFrame = nil
        phase = .ready
        active = game
    }

    func close() {
        hop.releaseKeys()
        lastFrame = nil
        phase = .ready
        active = nil
    }

    func start() {
        guard let game = active else { return }
        switch game {
        case .snake: snake.start()
        case .blocks: blocks.start()
        case .skyhop: hop.start()
        }
        newRecord = false
        lastFrame = nil
        phase = .playing
    }

    func pause() {
        guard phase == .playing else { return }
        hop.releaseKeys()
        phase = .paused
    }

    func resume() {
        guard phase == .paused else { return }
        lastFrame = nil
        phase = .playing
    }

    func primaryAction() {
        switch phase {
        case .ready, .over: start()
        case .playing: pause()
        case .paused: resume()
        }
    }

    /// Called when the arcade leaves the screen.
    func suspend() {
        pause()
        hop.releaseKeys()
    }

    /// One animation frame. Steps the active game by the time since the last frame, clamped so a
    /// hitch never teleports anything.
    func advance(to date: Date) {
        guard phase == .playing, let game = active else {
            lastFrame = nil
            return
        }
        // Switching to another app would swallow key-up events, so pause instead.
        if !NSApplication.shared.isActive {
            pause()
            return
        }
        let previous: Date = lastFrame ?? date
        lastFrame = date
        let raw: Double = date.timeIntervalSince(previous)
        let maxStep: Double = 1.0 / 20.0
        let dt: Double = min(max(raw, 0), maxStep)
        if dt <= 0 {
            return
        }
        switch game {
        case .snake: snake.step(dt)
        case .blocks: blocks.step(dt)
        case .skyhop: hop.step(dt)
        }
        checkForGameOver()
    }

    private func checkForGameOver() {
        guard phase == .playing, let game = active else { return }
        let alive: Bool
        switch game {
        case .snake: alive = snake.alive
        case .blocks: alive = blocks.alive
        case .skyhop: alive = hop.alive
        }
        if alive {
            return
        }
        let points: Int = score(for: game)
        let previousBest: Int = bestScore(game)
        if points > previousBest {
            best[game] = points
            UserDefaults.standard.set(points, forKey: game.defaultsKey)
            newRecord = true
        } else {
            newRecord = false
        }
        hop.releaseKeys()
        overAt = Date()
        phase = .over
    }

    // MARK: Keyboard

    /// Entry point from the local event monitor. Returns true for keys the arcade used.
    func handle(_ event: NSEvent) -> Bool {
        let key = ArcadeKey(keyCode: event.keyCode)
        if event.type == .keyUp {
            if active == .skyhop {
                hop.release(key)
            }
            return false
        }
        guard event.type == .keyDown else { return false }
        // Leave menu shortcuts (Command-Q, Command-W...) and text fields alone.
        let shortcutFlags: NSEvent.ModifierFlags = [.command, .control, .option]
        if !event.modifierFlags.intersection(shortcutFlags).isEmpty {
            return false
        }
        if let responder = event.window?.firstResponder, responder is NSText {
            return false
        }
        let isRepeat: Bool = event.isARepeat
        if let game = active {
            return handleGameKey(key, game: game, isRepeat: isRepeat)
        }
        return handleMenuKey(key, isRepeat: isRepeat)
    }

    private func handleMenuKey(_ key: ArcadeKey, isRepeat: Bool) -> Bool {
        switch key {
        case .left, .up:
            moveSelection(-1)
            return true
        case .right, .down:
            moveSelection(1)
            return true
        case .confirm, .space:
            if !isRepeat {
                open(selected)
            }
            return true
        default:
            return false
        }
    }

    private func moveSelection(_ delta: Int) {
        let all: [ArcadeGame] = ArcadeGame.allCases
        guard let index = all.firstIndex(of: selected) else { return }
        let next: Int = min(max(index + delta, 0), all.count - 1)
        selected = all[next]
    }

    private func handleGameKey(_ key: ArcadeKey, game: ArcadeGame, isRepeat: Bool) -> Bool {
        if key == .other {
            return false
        }
        if key == .escape {
            if !isRepeat {
                close()
            }
            return true
        }
        if game == .skyhop {
            // Steering is held, in any phase, so holding a direction while starting works.
            hop.press(key)
        }
        switch phase {
        case .ready, .over:
            // A short pause after game over stops a held or mashed key restarting at once.
            let settled: Bool = phase == .ready || Date().timeIntervalSince(overAt) > 0.5
            if settled && !isRepeat && (key == .space || key == .confirm) {
                start()
            }
        case .paused:
            if !isRepeat && (key == .space || key == .confirm || key == .pause) {
                resume()
            }
        case .playing:
            playKey(key, game: game, isRepeat: isRepeat)
        }
        return true
    }

    private func playKey(_ key: ArcadeKey, game: ArcadeGame, isRepeat: Bool) {
        if key == .pause {
            if !isRepeat {
                pause()
            }
            return
        }
        switch game {
        case .snake:
            switch key {
            case .up: snake.turn(.up)
            case .down: snake.turn(.down)
            case .left: snake.turn(.left)
            case .right: snake.turn(.right)
            case .space:
                if !isRepeat { pause() }
            default:
                break
            }
        case .blocks:
            switch key {
            case .left: blocks.move(-1)
            case .right: blocks.move(1)
            case .up:
                if !isRepeat { blocks.rotate() }
            case .down: blocks.softDrop()
            case .space, .confirm:
                if !isRepeat { blocks.hardDrop() }
            default:
                break
            }
            checkForGameOver()
        case .skyhop:
            if key == .space && !isRepeat {
                pause()
            }
        }
    }
}

/// Watches key presses and releases while the arcade is on screen.
@MainActor
private final class ArcadeKeyMonitor: ObservableObject {
    private var monitor: Any?

    func start(_ handler: @escaping @MainActor (NSEvent) -> Bool) {
        stop()
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .keyUp]) { event in
            handler(event) ? nil : event
        }
    }

    func stop() {
        if let monitor {
            NSEvent.removeMonitor(monitor)
            self.monitor = nil
        }
    }
}

// MARK: - Drawing

private enum ArcadeRenderer {
    static func draw(
        game: ArcadeGame,
        phase: ArcadePhase,
        time: Double,
        snake: ArcadeSnakeEngine,
        blocks: ArcadeBlocksEngine,
        hop: ArcadeHopEngine,
        context: GraphicsContext,
        size: CGSize
    ) {
        switch game {
        case .snake:
            drawSnake(snake, phase: phase, time: time, context: context, size: size)
        case .blocks:
            drawBlocks(blocks, phase: phase, context: context, size: size)
        case .skyhop:
            drawHop(hop, context: context, size: size)
        }
    }

    // MARK: Primitives

    static func circle(x: CGFloat, y: CGFloat, radius: CGFloat) -> Path {
        let side: CGFloat = radius * 2
        let rect = CGRect(x: x - radius, y: y - radius, width: side, height: side)
        return Path(ellipseIn: rect)
    }

    /// A square with a soft neon halo: a slightly larger translucent square behind it.
    static func glowCell(_ context: GraphicsContext, color: Color, x: CGFloat, y: CGFloat, cell: CGFloat) {
        let pad: CGFloat = cell * 0.12
        let outerSide: CGFloat = cell * 1.24
        let outer = CGRect(x: x - pad, y: y - pad, width: outerSide, height: outerSide)
        context.fill(Path(outer), with: .color(color.opacity(0.22)))
        let inset: CGFloat = cell * 0.06
        let innerSide: CGFloat = cell * 0.88
        let inner = CGRect(x: x + inset, y: y + inset, width: innerSide, height: innerSide)
        context.fill(Path(inner), with: .color(color))
    }

    /// The Sky Hop character: a small neon robot with a magenta visor.
    static func drawHopper(_ context: GraphicsContext, cx: CGFloat, cy: CGFloat, size: CGFloat, facingRight: Bool) {
        let half: CGFloat = size / 2

        let glowSide: CGFloat = size * 1.3
        let glowRect = CGRect(x: cx - half * 1.3, y: cy - half * 1.3, width: glowSide, height: glowSide)
        context.fill(Path(roundedRect: glowRect, cornerRadius: size * 0.35), with: .color(Neon.cyan.opacity(0.25)))

        let bodyRect = CGRect(x: cx - half, y: cy - half, width: size, height: size)
        context.fill(Path(roundedRect: bodyRect, cornerRadius: size * 0.25), with: .color(Neon.cyan))

        let visor = CGRect(x: cx - half * 0.8, y: cy - half * 0.45, width: size * 0.8, height: size * 0.26)
        context.fill(Path(visor), with: .color(Neon.magenta))

        let look: CGFloat = facingRight ? size * 0.1 : -size * 0.1
        let eyeOffsets: [CGFloat] = [-0.2, 0.2]
        for dx in eyeOffsets {
            let eyeX: CGFloat = cx + dx * size + look - size * 0.05
            let eye = CGRect(x: eyeX, y: cy - half * 0.38, width: size * 0.1, height: size * 0.12)
            context.fill(Path(eye), with: .color(Neon.onCyan))
        }

        let legOffsets: [CGFloat] = [-0.22, 0.22]
        var legs = Path()
        for dx in legOffsets {
            let legX: CGFloat = cx + dx * size
            legs.move(to: CGPoint(x: legX, y: cy + half))
            legs.addLine(to: CGPoint(x: legX, y: cy + half * 1.35))
        }
        context.stroke(legs, with: .color(Neon.cyanPale), lineWidth: size * 0.1)
    }

    // MARK: Snake

    static func drawSnake(_ game: ArcadeSnakeEngine, phase: ArcadePhase, time: Double, context: GraphicsContext, size: CGSize) {
        let cols = CGFloat(game.cols)
        let rows = CGFloat(game.rows)
        let cell: CGFloat = min(size.width / cols, size.height / rows)
        let left: CGFloat = (size.width - cell * cols) / 2
        let top: CGFloat = (size.height - cell * rows) / 2

        // Faint dot grid, filled in one go.
        let dotRadius: CGFloat = max(1, cell * 0.06)
        let dotSide: CGFloat = dotRadius * 2
        var dots = Path()
        for x in 0..<game.cols {
            for y in 0..<game.rows {
                let cx: CGFloat = left + (CGFloat(x) + 0.5) * cell
                let cy: CGFloat = top + (CGFloat(y) + 0.5) * cell
                dots.addEllipse(in: CGRect(x: cx - dotRadius, y: cy - dotRadius, width: dotSide, height: dotSide))
            }
        }
        context.fill(dots, with: .color(Neon.cyan.opacity(0.1)))

        if phase != .ready {
            let fx: CGFloat = left + (CGFloat(game.food.x) + 0.5) * cell
            let fy: CGFloat = top + (CGFloat(game.food.y) + 0.5) * cell
            let pulse: CGFloat = CGFloat(0.5 + 0.5 * sin(time * 5))
            let glowRadius: CGFloat = cell * (0.52 + 0.12 * pulse)
            context.fill(circle(x: fx, y: fy, radius: glowRadius), with: .color(Neon.magenta.opacity(0.3)))
            context.fill(circle(x: fx, y: fy, radius: cell * 0.36), with: .color(Neon.magenta))
        }

        // Tail first so the head sits on top.
        let parts: [ArcadeCell] = game.body
        let headColor: Color = phase == .over ? Neon.danger : Neon.cyanPale
        for index in parts.indices.reversed() {
            let part = parts[index]
            let color: Color = index == 0 ? headColor : Neon.cyan
            let x: CGFloat = left + CGFloat(part.x) * cell
            let y: CGFloat = top + CGFloat(part.y) * cell
            glowCell(context, color: color, x: x, y: y, cell: cell)
        }
    }

    // MARK: Blocks

    static func drawBlocks(_ game: ArcadeBlocksEngine, phase: ArcadePhase, context: GraphicsContext, size: CGSize) {
        let colCount: Int = ArcadeBlocksEngine.cols
        let rowCount: Int = ArcadeBlocksEngine.rows
        let cols = CGFloat(colCount)
        let rows = CGFloat(rowCount)
        // Ten-wide well plus a preview column.
        let totalCols: CGFloat = cols + 6
        let cell: CGFloat = min(size.height / rows, size.width / totalCols)
        let left: CGFloat = (size.width - cell * totalCols) / 2 + cell * 0.5
        let top: CGFloat = (size.height - cell * rows) / 2

        let well = CGRect(x: left, y: top, width: cell * cols, height: cell * rows)
        context.fill(Path(well), with: .color(Neon.backgroundSoft.opacity(0.7)))
        context.stroke(Path(well), with: .color(Neon.cyan.opacity(0.35)), lineWidth: 1.5)

        let board: [[Int]] = game.board
        for y in 0..<min(rowCount, board.count) {
            let row: [Int] = board[y]
            for x in 0..<min(colCount, row.count) {
                let value: Int = row[x]
                if value != 0 {
                    let cellX: CGFloat = left + CGFloat(x) * cell
                    let cellY: CGFloat = top + CGFloat(y) * cell
                    glowCell(context, color: ArcadePalette.piece(value - 1), x: cellX, y: cellY, cell: cell)
                }
            }
        }

        if phase == .playing || phase == .paused {
            let color: Color = ArcadePalette.piece(game.kind)
            let ghost: Int = game.ghostY()
            let ghostInset: CGFloat = cell * 0.1
            let ghostSide: CGFloat = cell * 0.8
            for c in game.cells {
                let gy: Int = ghost + c.y
                if gy >= 0 {
                    let gx: CGFloat = left + CGFloat(game.px + c.x) * cell + ghostInset
                    let gyPoint: CGFloat = top + CGFloat(gy) * cell + ghostInset
                    let rect = CGRect(x: gx, y: gyPoint, width: ghostSide, height: ghostSide)
                    context.stroke(Path(rect), with: .color(color.opacity(0.45)), lineWidth: 1.5)
                }
            }
            for c in game.cells {
                let cy: Int = game.py + c.y
                if cy >= 0 {
                    let cellX: CGFloat = left + CGFloat(game.px + c.x) * cell
                    let cellY: CGFloat = top + CGFloat(cy) * cell
                    glowCell(context, color: color, x: cellX, y: cellY, cell: cell)
                }
            }
        }

        // Next piece preview.
        let previewLeft: CGFloat = left + cell * (cols + 1.5)
        let box = CGRect(x: previewLeft - cell * 0.5, y: top + cell * 0.5, width: cell * 4.5, height: cell * 4)
        context.stroke(Path(box), with: .color(Neon.magenta.opacity(0.5)), lineWidth: 1.5)
        if phase != .ready {
            let small: CGFloat = cell * 0.9
            let nextColor: Color = ArcadePalette.piece(game.nextKind)
            let shape: [ArcadeCell] = ArcadeBlocksEngine.pieces[game.nextKind]
            for c in shape {
                let cellX: CGFloat = previewLeft + CGFloat(c.x + 1) * small
                let cellY: CGFloat = top + (CGFloat(c.y) + 2.2) * small + cell * 0.4
                glowCell(context, color: nextColor, x: cellX, y: cellY, cell: small)
            }
        }
        let labelSize: CGFloat = max(9, cell * 0.5)
        let label = Text("NEXT")
            .font(NeonFont.display(labelSize))
            .foregroundColor(Neon.magenta.opacity(0.85))
        context.draw(label, at: CGPoint(x: previewLeft + cell * 1.75, y: top + cell * 4.8), anchor: .top)
    }

    // MARK: Sky Hop

    static func drawHop(_ game: ArcadeHopEngine, context: GraphicsContext, size: CGSize) {
        let scale: CGFloat = size.width / CGFloat(ArcadeHopEngine.worldW)
        let camera: Double = game.camera
        let height: CGFloat = size.height

        func screenY(_ worldY: Double) -> CGFloat {
            return height - CGFloat(worldY - camera) * scale
        }

        // Scrolling horizon lines give a sense of climbing.
        let spacing: Double = 20
        var lineY: Double = (camera / spacing).rounded(.down) * spacing
        var lines = Path()
        while lineY < camera + ArcadeHopEngine.worldH {
            let sy: CGFloat = screenY(lineY)
            lines.move(to: CGPoint(x: 0, y: sy))
            lines.addLine(to: CGPoint(x: size.width, y: sy))
            lineY += spacing
        }
        context.stroke(lines, with: .color(Neon.magenta.opacity(0.12)), lineWidth: 1)

        let platformWidth: CGFloat = CGFloat(ArcadeHopEngine.platformW) * scale
        for p in game.platforms {
            let color: Color = p.moving ? Neon.magenta : Neon.cyan
            let top: CGFloat = screenY(p.y)
            let left: CGFloat = CGFloat(p.x) * scale
            let glow = CGRect(x: left - 2, y: top - 3, width: platformWidth + 4, height: 3 * scale + 6)
            context.fill(Path(glow), with: .color(color.opacity(0.25)))
            let slab = CGRect(x: left, y: top, width: platformWidth, height: 2.5 * scale)
            context.fill(Path(slab), with: .color(color))
        }

        let hopperSize: CGFloat = CGFloat(ArcadeHopEngine.hopperSize) * scale
        let cx: CGFloat = CGFloat(game.x) * scale
        let cy: CGFloat = screenY(game.y) - hopperSize * 0.85
        drawHopper(context, cx: cx, cy: cy, size: hopperSize, facingRight: game.facingRight)
        // Draw a second copy while wrapping across an edge.
        if cx < hopperSize {
            drawHopper(context, cx: cx + size.width, cy: cy, size: hopperSize, facingRight: game.facingRight)
        }
        if cx > size.width - hopperSize {
            drawHopper(context, cx: cx - size.width, cy: cy, size: hopperSize, facingRight: game.facingRight)
        }
    }

    // MARK: Menu icons

    static func drawIcon(game: ArcadeGame, context: GraphicsContext, size: CGSize) {
        let w: CGFloat = size.width
        let h: CGFloat = size.height
        let cell: CGFloat = h / 8
        switch game {
        case .snake:
            let path: [ArcadeCell] = ArcadeBlocksEngine.shape([2, 5, 3, 5, 4, 5, 5, 5, 5, 4, 5, 3, 6, 3, 7, 3])
            let originX: CGFloat = w / 2 - 5 * cell
            let side: CGFloat = cell * 0.9
            for (index, part) in path.enumerated() {
                let color: Color = index == path.count - 1 ? Neon.cyanPale : Neon.cyan
                let rect = CGRect(x: originX + CGFloat(part.x) * cell, y: CGFloat(part.y) * cell, width: side, height: side)
                context.fill(Path(rect), with: .color(color))
            }
            let food: Path = circle(x: w / 2 + 4.5 * cell, y: 3.45 * cell, radius: cell * 0.4)
            context.fill(food, with: .color(Neon.magenta))
        case .blocks:
            // x, y, colour index
            let cells: [[Int]] = [
                [0, 7, 0], [1, 7, 0], [2, 7, 1], [3, 7, 1], [4, 7, 2],
                [0, 6, 3], [1, 6, 1], [3, 6, 2], [4, 6, 2],
                [2, 2, 4], [2, 3, 4], [3, 3, 4], [2, 4, 4],
            ]
            let left: CGFloat = w / 2 - 2.5 * cell
            let side: CGFloat = cell * 0.92
            for item in cells {
                let rect = CGRect(x: left + CGFloat(item[0]) * cell, y: CGFloat(item[1]) * cell, width: side, height: side)
                context.fill(Path(rect), with: .color(ArcadePalette.piece(item[2])))
            }
        case .skyhop:
            let ledges: [CGPoint] = [
                CGPoint(x: 0.25, y: 0.85),
                CGPoint(x: 0.62, y: 0.62),
                CGPoint(x: 0.35, y: 0.38),
                CGPoint(x: 0.7, y: 0.16),
            ]
            for ledge in ledges {
                let rect = CGRect(x: w * ledge.x - cell * 1.2, y: h * ledge.y, width: cell * 2.4, height: cell * 0.35)
                context.fill(Path(rect), with: .color(Neon.cyan))
            }
            drawHopper(context, cx: w * 0.35, cy: h * 0.38 - cell * 1.1, size: cell * 1.1, facingRight: true)
        }
        context.stroke(Path(CGRect(origin: .zero, size: size)), with: .color(Neon.cyan.opacity(0.25)), lineWidth: 1)
    }
}

// MARK: - Views

/// The Arcade tab: a menu of three games, each played full size inside this view.
@MainActor
struct GamesView: View {
    @StateObject private var arcade = ArcadeController()
    @StateObject private var keys = ArcadeKeyMonitor()

    init() {}

    var body: some View {
        ZStack {
            if let game = arcade.active {
                ArcadePlayScreen(arcade: arcade, game: game)
                    .transition(.opacity)
            } else {
                ArcadeMenu(arcade: arcade)
                    .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .animation(.easeInOut(duration: 0.18), value: arcade.active)
        .onAppear {
            let controller = arcade
            keys.start { event in controller.handle(event) }
        }
        .onDisappear {
            keys.stop()
            arcade.suspend()
        }
    }
}

@MainActor
private struct ArcadeMenu: View {
    @ObservedObject var arcade: ArcadeController

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            NeonTitle(text: "Arcade", size: 34)
            Text("Three quick games for the keyboard. Arrow keys choose, Return plays, Esc comes back here.")
                .font(NeonFont.body(15))
                .foregroundColor(Neon.textSecondary)
            HStack(alignment: .top, spacing: 20) {
                ForEach(ArcadeGame.allCases) { game in
                    ArcadeGameCard(
                        game: game,
                        best: arcade.bestScore(game),
                        selected: arcade.selected == game,
                        onHover: { arcade.selected = game },
                        onPlay: { arcade.open(game) }
                    )
                }
            }
            .padding(.top, 8)
            Spacer(minLength: 0)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

@MainActor
private struct ArcadeGameCard: View {
    let game: ArcadeGame
    let best: Int
    let selected: Bool
    let onHover: () -> Void
    let onPlay: () -> Void

    private var bestText: String { "Best " + String(best) }

    var body: some View {
        Button(action: onPlay) {
            VStack(alignment: .leading, spacing: 10) {
                ArcadeGameIcon(game: game, lit: selected)
                    .aspectRatio(1.4, contentMode: .fit)
                Text(game.title)
                    .font(NeonFont.display(20))
                    .foregroundColor(selected ? Neon.cyan : Neon.text)
                Text(game.blurb)
                    .font(NeonFont.body(14))
                    .foregroundColor(Neon.textSecondary)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                Text(game.hint)
                    .font(NeonFont.body(12))
                    .foregroundColor(Neon.textMuted)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                Text(bestText)
                    .font(NeonFont.body(14, bold: true))
                    .foregroundColor(Neon.magenta)
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .neonPanel(highlighted: selected)
            .contentShape(HudShape(cut: 12))
        }
        .buttonStyle(.plain)
        .frame(minWidth: 170, maxWidth: 280)
        .scaleEffect(selected ? 1.03 : 1.0)
        .animation(.easeOut(duration: 0.15), value: selected)
        .onHover { inside in
            if inside {
                onHover()
            }
        }
        .help("Play " + game.title)
    }
}

@MainActor
private struct ArcadeGameIcon: View {
    let game: ArcadeGame
    let lit: Bool

    var body: some View {
        let which: ArcadeGame = game
        Canvas { context, size in
            ArcadeRenderer.drawIcon(game: which, context: context, size: size)
        }
        .background(Neon.background.opacity(lit ? 0.9 : 0.6))
    }
}

@MainActor
private struct ArcadePlayScreen: View {
    @ObservedObject var arcade: ArcadeController
    let game: ArcadeGame

    var body: some View {
        let paused: Bool = arcade.phase != .playing
        TimelineView(.animation(minimumInterval: nil, paused: paused)) { timeline in
            ArcadePlayLayout(arcade: arcade, game: game, date: timeline.date)
                .onChange(of: timeline.date) { newDate in
                    arcade.advance(to: newDate)
                }
        }
        .padding(24)
    }
}

/// Board plus side panel; rebuilt every animation frame so the numbers stay live.
@MainActor
private struct ArcadePlayLayout: View {
    @ObservedObject var arcade: ArcadeController
    let game: ArcadeGame
    let date: Date

    var body: some View {
        let points: Int = arcade.currentScore
        let record: Int = max(arcade.bestScore(game), points)
        let level: Int? = game == .blocks ? arcade.blocks.level : nil
        let lines: Int? = game == .blocks ? arcade.blocks.lines : nil
        let time: Double = date.timeIntervalSinceReferenceDate
        HStack(alignment: .top, spacing: 24) {
            ArcadeBoard(
                game: game,
                phase: arcade.phase,
                time: time,
                message: arcade.message,
                snake: arcade.snake,
                blocks: arcade.blocks,
                hop: arcade.hop
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            ArcadeSidePanel(
                game: game,
                phase: arcade.phase,
                score: points,
                best: record,
                level: level,
                lines: lines,
                onBack: { arcade.close() },
                onPrimary: { arcade.primaryAction() }
            )
            .frame(width: 250)
            .frame(maxHeight: .infinity)
        }
    }
}

@MainActor
private struct ArcadeBoard: View {
    let game: ArcadeGame
    let phase: ArcadePhase
    let time: Double
    let message: String?
    let snake: ArcadeSnakeEngine
    let blocks: ArcadeBlocksEngine
    let hop: ArcadeHopEngine

    var body: some View {
        // Local copies, so the canvas closure captures plain values rather than the view.
        let which: ArcadeGame = game
        let currentPhase: ArcadePhase = phase
        let now: Double = time
        let snakeEngine: ArcadeSnakeEngine = snake
        let blocksEngine: ArcadeBlocksEngine = blocks
        let hopEngine: ArcadeHopEngine = hop
        ZStack {
            HudShape(cut: 14)
                .fill(Neon.background)
                .shadow(color: Neon.cyan.opacity(0.22), radius: 12)
            Canvas { context, size in
                ArcadeRenderer.draw(
                    game: which,
                    phase: currentPhase,
                    time: now,
                    snake: snakeEngine,
                    blocks: blocksEngine,
                    hop: hopEngine,
                    context: context,
                    size: size
                )
            }
            .clipShape(HudShape(cut: 14))
            HudShape(cut: 14)
                .stroke(Neon.cyan.opacity(0.7), lineWidth: 2)
            if let message {
                ArcadeMessageBox(text: message)
            }
        }
        .aspectRatio(game.aspect, contentMode: .fit)
    }
}

@MainActor
private struct ArcadeMessageBox: View {
    let text: String

    var body: some View {
        Text(text)
            .font(NeonFont.display(18))
            .foregroundColor(Neon.text)
            .multilineTextAlignment(.center)
            .lineSpacing(6)
            .padding(.horizontal, 26)
            .padding(.vertical, 18)
            .background(HudShape(cut: 10).fill(Neon.background.opacity(0.85)))
            .overlay(HudShape(cut: 10).stroke(Neon.magenta, lineWidth: 1))
            .shadow(color: Neon.magenta.opacity(0.35), radius: 10)
            .padding(16)
            .allowsHitTesting(false)
    }
}

@MainActor
private struct ArcadeSidePanel: View {
    let game: ArcadeGame
    let phase: ArcadePhase
    let score: Int
    let best: Int
    let level: Int?
    let lines: Int?
    let onBack: () -> Void
    let onPrimary: () -> Void

    private var primaryTitle: String {
        switch phase {
        case .ready: return "Start"
        case .playing: return "Pause"
        case .paused: return "Resume"
        case .over: return "Play again"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            NeonTitle(text: game.title, size: 24)
            ArcadeStatRow(label: "SCORE", value: score, color: Neon.text)
            ArcadeStatRow(label: "BEST", value: best, color: Neon.magenta)
            if let level, let lines {
                HStack(alignment: .top, spacing: 24) {
                    ArcadeStatRow(label: "LEVEL", value: level, color: Neon.cyan)
                    ArcadeStatRow(label: "LINES", value: lines, color: Neon.cyanPale)
                }
            }
            Rectangle()
                .fill(Neon.cyan.opacity(0.2))
                .frame(height: 1)
            Text("CONTROLS")
                .font(NeonFont.body(11, bold: true))
                .foregroundColor(Neon.textMuted)
            ForEach(game.controls, id: \.self) { item in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(item.keys)
                        .font(NeonFont.body(12, bold: true))
                        .foregroundColor(Neon.cyan)
                        .frame(width: 92, alignment: .leading)
                    Text(item.action)
                        .font(NeonFont.body(12))
                        .foregroundColor(Neon.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            HStack(spacing: 10) {
                Button(action: onBack) {
                    Label("Back", systemImage: "chevron.left")
                }
                .buttonStyle(NeonButtonStyle())
                .help("Back to the arcade menu (Esc)")
                Button(primaryTitle, action: onPrimary)
                    .buttonStyle(NeonButtonStyle(prominent: true))
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .neonPanel()
    }
}

@MainActor
private struct ArcadeStatRow: View {
    let label: String
    let value: Int
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(NeonFont.body(11, bold: true))
                .foregroundColor(Neon.textMuted)
            Text(String(value))
                .font(NeonFont.display(24))
                .foregroundColor(color)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
        }
    }
}
