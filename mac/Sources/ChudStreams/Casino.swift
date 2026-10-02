import SwiftUI

/// Blackjack and roulette, local play-money only. The dealer is the app logo, drawn as a
/// bobbing sprite. No server is started, so the firewall never sees an incoming connection.
enum CasinoGame: String, Identifiable {
    case blackjack = "Blackjack"
    case roulette = "Roulette"

    var id: String { rawValue }

    var blurb: String {
        switch self {
        case .blackjack: return "Play money. Hit or stand against the dealer."
        case .roulette: return "European wheel. Bet a colour or a number."
        }
    }
}

struct CasinoMenuRow: View {
    let onPlay: (CasinoGame) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Table games")
                .font(NeonFont.display(22))
                .foregroundColor(Neon.cyan)
            Text("Same menu as the other games. Chips are play money and stay on this Mac.")
                .font(NeonFont.body(14))
                .foregroundColor(Neon.textSecondary)
            HStack(alignment: .top, spacing: 20) {
                ForEach([CasinoGame.blackjack, .roulette]) { game in
                    Button { onPlay(game) } label: {
                        VStack(alignment: .leading, spacing: 8) {
                            if let badge = BrandImage.badge {
                                Image(nsImage: badge)
                                    .resizable()
                                    .frame(width: 72, height: 72)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                            }
                            Text(game.rawValue)
                                .font(NeonFont.display(20))
                                .foregroundColor(Neon.cyan)
                            Text(game.blurb)
                                .font(NeonFont.body(14))
                                .foregroundColor(Neon.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(18)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .neonPanel(highlighted: true)
                    }
                    .buttonStyle(.plain)
                    .frame(minWidth: 170, maxWidth: 280)
                }
            }
        }
    }
}

struct CasinoScreen: View {
    let game: CasinoGame
    let onClose: () -> Void

    var body: some View {
        Group {
            switch game {
            case .blackjack: BlackjackTable(onClose: onClose)
            case .roulette: RouletteTable(onClose: onClose)
            }
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

private struct DealerSprite: View {
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 12.0)) { timeline in
            let bob = sin(timeline.date.timeIntervalSinceReferenceDate * 2.2) * 6
            VStack(spacing: 4) {
                if let badge = BrandImage.badge {
                    Image(nsImage: badge)
                        .resizable()
                        .frame(width: 96, height: 96)
                        .offset(y: bob)
                        .shadow(color: Neon.magenta.opacity(0.6), radius: 12)
                }
                Text("Dealer")
                    .font(NeonFont.body(12))
                    .foregroundColor(Neon.magenta)
            }
        }
    }
}

@MainActor
private struct BlackjackTable: View {
    let onClose: () -> Void
    @State private var deck: [Int] = []
    @State private var player: [Int] = []
    @State private var dealer: [Int] = []
    @State private var hideHole = true
    @State private var note = "Hit or stand."

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                NeonTitle(text: "Blackjack", size: 32)
                Spacer()
                Button("Back") { onClose() }.buttonStyle(NeonButtonStyle())
            }
            HStack(alignment: .top, spacing: 24) {
                DealerSprite()
                VStack(alignment: .leading, spacing: 8) {
                    Text("Dealer  \(dealerShown)")
                        .font(NeonFont.display(18))
                        .foregroundColor(Neon.text)
                    Text(cards(dealer, hideFirst: hideHole))
                        .font(NeonFont.body(16))
                        .foregroundColor(Neon.cyanPale)
                    Text("You  \(handTotal(player))")
                        .font(NeonFont.display(18))
                        .foregroundColor(Neon.text)
                        .padding(.top, 8)
                    Text(cards(player, hideFirst: false))
                        .font(NeonFont.body(16))
                        .foregroundColor(Neon.cyan)
                    Text(note)
                        .font(NeonFont.body(14))
                        .foregroundColor(Neon.textSecondary)
                }
            }
            HStack(spacing: 10) {
                Button("Deal") { deal() }.buttonStyle(NeonButtonStyle())
                Button("Hit") { hit() }.buttonStyle(NeonButtonStyle()).disabled(!hideHole)
                Button("Stand") { stand() }.buttonStyle(NeonButtonStyle()).disabled(!hideHole || player.isEmpty)
            }
        }
        .onAppear { if player.isEmpty { deal() } }
    }

    private var dealerShown: String {
        hideHole ? (dealer.isEmpty ? "" : "?") : "\(handTotal(dealer))"
    }

    private func deal() {
        deck = (0..<52).shuffled()
        player = [draw(), draw()]
        dealer = [draw(), draw()]
        hideHole = true
        note = handTotal(player) == 21 ? "Blackjack." : "Hit or stand."
        if handTotal(player) == 21 { stand() }
    }

    private func hit() {
        guard hideHole, !deck.isEmpty else { return }
        player.append(draw())
        if handTotal(player) > 21 {
            hideHole = false
            note = "Bust. \(handTotal(player))."
        }
    }

    private func stand() {
        guard hideHole else { return }
        hideHole = false
        while handTotal(dealer) < 17, !deck.isEmpty { dealer.append(draw()) }
        let you = handTotal(player)
        let house = handTotal(dealer)
        if you > 21 { note = "Bust." }
        else if house > 21 || you > house { note = "You win. \(you) to \(house)." }
        else if you == house { note = "Push. \(you)." }
        else { note = "Dealer wins. \(house) to \(you)." }
    }

    private func draw() -> Int { deck.popLast() ?? 0 }

    private func cards(_ hand: [Int], hideFirst: Bool) -> String {
        hand.enumerated().map { index, card in
            (hideFirst && index == 0) ? "??" : label(card)
        }.joined(separator: "   ")
    }

    private func label(_ card: Int) -> String {
        let ranks = ["A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"]
        let suits = ["♠", "♥", "♦", "♣"]
        return ranks[card % 13] + suits[card / 13]
    }

    private func handTotal(_ hand: [Int]) -> Int {
        var sum = 0
        var aces = 0
        for card in hand {
            let rank = card % 13
            if rank == 0 { aces += 1; sum += 11 }
            else if rank >= 9 { sum += 10 }
            else { sum += rank + 1 }
        }
        while sum > 21 && aces > 0 { sum -= 10; aces -= 1 }
        return sum
    }
}

@MainActor
private struct RouletteTable: View {
    let onClose: () -> Void
    @AppStorage("casino.balance") private var balance = 1000
    @State private var stake = 10
    @State private var pick = "red"
    @State private var numberField = ""
    @State private var result = "Place a bet."
    @State private var spinning = false
    @State private var landed: Int? = nil

    private static let reds: Set<Int> = [1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                NeonTitle(text: "Roulette", size: 32)
                Spacer()
                Text("Chips \(balance)")
                    .font(NeonFont.display(18))
                    .foregroundColor(Neon.magenta)
                Button("Back") { onClose() }.buttonStyle(NeonButtonStyle())
            }
            DealerSprite()
            Text(landed.map { "Ball on \($0)  \(Self.colorName($0))" } ?? "Wheel is closed.")
                .font(NeonFont.display(22))
                .foregroundColor(Neon.cyan)
            Text(result)
                .font(NeonFont.body(15))
                .foregroundColor(Neon.textSecondary)
            HStack(spacing: 8) {
                ForEach(["red", "black", "even", "odd"], id: \.self) { name in
                    Button(name) { pick = name; numberField = "" }
                        .buttonStyle(NeonButtonStyle())
                        .opacity(pick == name && numberField.isEmpty ? 1 : 0.5)
                }
                TextField("Number 0–36", text: $numberField)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 120)
            }
            HStack(spacing: 8) {
                Button("Stake 10") { stake = 10 }.buttonStyle(NeonButtonStyle())
                Button("Stake 50") { stake = 50 }.buttonStyle(NeonButtonStyle())
                Button(spinning ? "Spinning…" : "Spin \(stake)") { spin() }
                    .buttonStyle(NeonButtonStyle())
                    .disabled(spinning || balance < stake)
            }
        }
    }

    private func spin() {
        guard balance >= stake else { return }
        spinning = true
        let ball = Int.random(in: 0...36)
        Task {
            try? await Task.sleep(nanoseconds: 900_000_000)
            landed = ball
            let won = payout(ball)
            balance += won
            result = won > 0 ? "Won \(won) chips." : "Lost \(stake) chips."
            spinning = false
        }
    }

    private func payout(_ ball: Int) -> Int {
        if let number = Int(numberField.trimmingCharacters(in: .whitespaces)), (0...36).contains(number) {
            return ball == number ? stake * 35 : -stake
        }
        let hit: Bool
        switch pick {
        case "red": hit = Self.reds.contains(ball)
        case "black": hit = ball != 0 && !Self.reds.contains(ball)
        case "even": hit = ball != 0 && ball % 2 == 0
        case "odd": hit = ball % 2 == 1
        default: hit = false
        }
        return hit ? stake : -stake
    }

    private static func colorName(_ ball: Int) -> String {
        if ball == 0 { return "green" }
        return reds.contains(ball) ? "red" : "black"
    }
}
