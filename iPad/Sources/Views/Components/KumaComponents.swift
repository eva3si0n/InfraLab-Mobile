import SwiftUI

// MARK: - Heartbeat bar (shared visual component)

struct KumaHeartbeatBar: View {
    let beats: [KumaHeartbeat]

    var body: some View {
        HStack(spacing: 3) {
            ForEach(Array(beats.enumerated()), id: \.offset) { _, beat in
                RoundedRectangle(cornerRadius: 2.5, style: .continuous)
                    .fill(color(for: beat.status))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private func color(for status: Int) -> Color {
        switch status {
        case 1: return Color(red: 0.36, green: 0.84, blue: 0.55)
        case 0: return Color(red: 0.86, green: 0.24, blue: 0.30)
        case 2: return Color(red: 0.97, green: 0.65, blue: 0.20)
        case 3: return Color(red: 0.27, green: 0.55, blue: 0.95)
        default: return Color.gray.opacity(0.35)
        }
    }
}

// MARK: - Monitor card (full-width iPad layout)

struct MonitorCard: View {
    let monitor: MonitorStatus

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Circle()
                    .fill(monitor.isUp ? Color.green : Color.red)
                    .frame(width: 11, height: 11)
                    .shadow(color: (monitor.isUp ? Color.green : Color.red).opacity(0.6), radius: 5)

                Text(monitor.name)
                    .font(.headline)

                Spacer()

                if let ms = monitor.latency {
                    Text("\(ms) ms")
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                }

                Text(monitor.isUp ? "UP" : "DOWN")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(monitor.isUp ? .green : .red)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(
                        (monitor.isUp ? Color.green : Color.red).opacity(0.15),
                        in: Capsule()
                    )
            }

            if !monitor.recentBeats.isEmpty {
                KumaHeartbeatBar(beats: monitor.recentBeats)
                    .frame(height: 28)
            }

            Label(uptimeText(monitor.uptime24h) + " · 24h uptime", systemImage: "clock")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    private func uptimeText(_ ratio: Double) -> String {
        String(format: "%.2f%%", ratio * 100)
    }
}

// MARK: - Group summary row (sidebar list item)

struct GroupSummaryRow: View {
    let name: String
    let monitors: [MonitorStatus]

    private var up: Int    { monitors.filter(\.isUp).count }
    private var total: Int { monitors.count }
    private var allUp: Bool { up == total }
    private var dotColor: Color { allUp ? .green : (up == 0 ? .red : .orange) }

    private var aggregateBeats: [KumaHeartbeat] {
        let lists = monitors.map(\.recentBeats).filter { !$0.isEmpty }
        guard !lists.isEmpty else { return [] }
        let n = lists.map(\.count).max() ?? 0
        var result: [KumaHeartbeat] = []
        for d in 0..<n {
            var present = false, anyDown = false
            for list in lists where list.count > d {
                present = true
                if list[list.count - 1 - d].status == 0 { anyDown = true }
            }
            if present { result.append(KumaHeartbeat(status: anyDown ? 0 : 1, ping: nil, time: "")) }
        }
        return result.reversed()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 8) {
                Circle()
                    .fill(dotColor)
                    .frame(width: 9, height: 9)
                    .shadow(color: dotColor.opacity(0.6), radius: 3)

                Text(name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)

                Spacer()

                Text("\(up)/\(total)")
                    .font(.caption.monospacedDigit().weight(.semibold))
                    .foregroundStyle(allUp ? .green : .orange)
            }

            if !aggregateBeats.isEmpty {
                KumaHeartbeatBar(beats: aggregateBeats)
                    .frame(height: 10)
            }
        }
        .padding(.vertical, 4)
    }
}
