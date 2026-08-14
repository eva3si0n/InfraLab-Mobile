import SwiftUI

/// Транзит: через какие автономные системы идёт путь от RU-входа до эндпоинта плеча.
///
/// Меряется по АНДЕРЛЕЮ (traceroute до эндпоинта), а не сквозь туннель: RTT сквозь
/// туннель показал бы деградацию транзита только когда она уже съела задержку, и не
/// сказал бы, на каком участке.
///
/// ⚠️ Флаг — страна, где реально стоят ХОПЫ этой AS, а НЕ страна её регистрации.
/// Разница принципиальна: AS6939 (Hurricane Electric) зарегистрирована в США, но её
/// маршрутизаторы на нашем пути стоят в Хельсинки.
///
/// Цепочка нарисована СВЕРХУ ВНИЗ, в отличие от веб-панели, где она горизонтальная:
/// на телефоне горизонтальная всё равно переносится и перестаёт читаться как путь.
struct CascadePathsView: View {
    let paths: [CascadeAPI.PathInfo]

    var body: some View {
        Group {
            if paths.isEmpty {
                ContentUnavailableView("Нет данных о транзите", systemImage: "point.topleft.down.curvedto.point.bottomright.up",
                    description: Text("Зонд ещё не отработал или vpncascade недоступен"))
            } else {
                List {
                    ForEach(hosts, id: \.self) { host in
                        Section(host) {
                            ForEach(paths.filter { $0.host == host }, id: \.leg) { p in
                                legBlock(p)
                            }
                        }
                    }
                    Section {
                        Text("Путь до эндпоинта каждого плеча, замер раз в 12 минут портом самого плеча. "
                             + "Флаг — где реально стоят хопы этой AS, а не где она зарегистрирована.")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Транзит")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var hosts: [String] {
        var seen = Set<String>()
        return paths.compactMap { seen.insert($0.host).inserted ? $0.host : nil }
    }

    @ViewBuilder
    private func legBlock(_ p: CascadeAPI.PathInfo) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(p.leg.uppercased())
                    .font(.caption.weight(.bold)).monospaced()
                    .padding(.horizontal, 7).padding(.vertical, 2)
                    .background(Color.secondary.opacity(0.15)).clipShape(RoundedRectangle(cornerRadius: 6))
                if p.stale == true {
                    Text("зонд молчит").font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.red.opacity(0.18)).foregroundStyle(.red)
                        .clipShape(Capsule())
                }
                Spacer()
                Text("\(p.hops ?? 0) \(plural(p.hops ?? 0)) · с \(fmtTime(p.changedAt))")
                    .font(.caption2).foregroundStyle(.secondary)
            }

            ForEach(Array((p.asns ?? []).enumerated()), id: \.offset) { idx, a in
                HStack(alignment: .top, spacing: 8) {
                    // Вертикальный соединитель: точка на каждом звене, чёрточка между.
                    VStack(spacing: 0) {
                        Circle().fill(Color.secondary.opacity(0.5)).frame(width: 6, height: 6)
                        if idx < (p.asns?.count ?? 0) - 1 {
                            Rectangle().fill(Color.secondary.opacity(0.25)).frame(width: 1.5)
                        }
                    }
                    .frame(width: 8)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("AS\(a.num)").font(.caption.weight(.semibold)).monospaced()
                        if a.name != nil || a.cc != nil {
                            Text("\(flag(a.cc))\(a.name ?? "")")
                                .font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                }
                .padding(.bottom, idx < (p.asns?.count ?? 0) - 1 ? 2 : 0)
            }

            // История: только ПРЕДЫДУЩИЕ состояния — текущее и есть цепочка выше.
            // Если смена одна, пишем это явно: пустота на месте истории читается
            // как «данные потеряли» (поймано на веб-панели 13.08.2026).
            if let ch = p.changes, ch.count > 1 {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(ch.dropFirst().enumerated()), id: \.offset) { _, c in
                        Text("до \(fmtTime(c.time)) · \(asPath(c.asPath))")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
                .padding(.leading, 16).padding(.top, 2)
            } else if let ch = p.changes, ch.count == 1 {
                Text("путь не менялся с \(fmtTime(ch[0].time))")
                    .font(.caption2).foregroundStyle(.secondary)
                    .padding(.leading, 16).padding(.top, 2)
            }
        }
        .padding(.vertical, 4)
    }

    /// Флаг страны из двухбуквенного кода. Пусто — если кода нет: выдумывать нельзя.
    private func flag(_ cc: String?) -> String {
        guard let cc, cc.count == 2 else { return "" }
        var s = ""
        for ch in cc.uppercased().unicodeScalars {
            if let v = UnicodeScalar(127397 + ch.value) { s.unicodeScalars.append(v) }
        }
        return s + " "
    }

    /// «1 хоп», «3 хопа», «5 хопов» — «3 хопов» бросается в глаза.
    private func plural(_ n: Int) -> String {
        let m10 = n % 10, m100 = n % 100
        if m10 == 1 && m100 != 11 { return "хоп" }
        if (2...4).contains(m10) && !(12...14).contains(m100) { return "хопа" }
        return "хопов"
    }

    private func asPath(_ s: String?) -> String {
        (s ?? "").split(separator: " ").map { "AS\($0)" }.joined(separator: " → ")
    }

    private func fmtTime(_ unix: Double?) -> String {
        guard let unix, unix > 0 else { return "—" }
        let f = DateFormatter()
        f.timeZone = TimeZone(secondsFromGMT: 5 * 3600)   // MSK+2 (UTC+5), как везде в приложении
        f.dateFormat = "d MMM, HH:mm"
        f.locale = Locale(identifier: "ru_RU")
        return f.string(from: Date(timeIntervalSince1970: unix))
    }
}
