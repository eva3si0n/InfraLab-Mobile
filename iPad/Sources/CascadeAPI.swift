import Foundation

// Полный ответ vpncascade `GET /api/cascade`.
//
// Зачем. Раньше экран каскада собирался в приложении САМ — десятью запросами PromQL
// через Grafana-прокси: активное плечо, длительность, RTT, throughput, месячный трафик,
// история миграций. Ровно ту же логику считает сервис, и каждая новая возможность
// веб-панели (PBR, контур квоты, транзит) требовала отдельной реализации на трёх
// платформах и расходилась с вебом. Теперь клиент тонкий: одна выборка, один источник
// правды, новые поля появляются в приложении почти даром.
//
// Снаружи дома этот путь закрыт Cloudflare Access — запрос идёт с заголовками
// service token (см. `AppState.cfRequest`). Внутри LAN работает и без них.
//
// ⚠️ Все поля опциональны намеренно: сервис их добавляет по ходу (paths приехали
// 13.08.2026), и старая сборка приложения не должна падать на новом payload.

struct CascadeAPI {

    struct Beat: Codable {
        let status: Int?
        let ping: Double?
        let time: String?
    }

    struct MonitorBlock: Codable {
        let isUp: Bool?
        let uptime24h: Double?
        let recentBeats: [Beat]?
    }

    struct Segment: Codable {
        let host: String
        let title: String?
        let label: String?
        let activeLeg: String?
        let activeSeconds: Double?
        let rtt: [String: Double]?
        let txBps: Double?
        let rxBps: Double?
        let healthy: Bool?
        let cascade: MonitorBlock?
        let cascadeMonitored: Bool?
        /// "auto" | "sto" | "ams" | … — что сейчас зафиксировано вручную.
        let override: String?
        let switchLegs: [String]?
        let primaryLeg: String?
        let manual: Bool?
        let inputUp: Bool?
        let inputAge: Double?
        let txSeries: [Double]?
        let rxSeries: [Double]?
        /// "rkn" | "udm" — деки в вебе делятся по этому признаку, история тоже.
        let group: String?
        let fqdn: String?
        let ip: String?
    }

    struct Leg: Codable {
        let leg: String
        let homeRTT: Double?
        let txBytes: Double?
        let limitBytes: Double?
    }

    struct Migration: Codable {
        let host: String?
        let label: String?
        let group: String?
        let from: String?
        let to: String?
        let time: Double?
        let reason: String?
    }

    struct FailoverSide: Codable {
        let host: String?
        let up: Bool?
        let loss: Double?
        let hop1: Double?
        let outages: Int?
        let cleanSec: Double?
    }

    struct Failover: Codable {
        let enabled: Bool?
        let dryRun: Bool?
        let primaryHost: String?
        let backupHost: String?
        let primaryUp: Bool?
        let backupUp: Bool?
        let failedOver: Bool?
        let sides: [FailoverSide]?
        let staleSec: Double?
        let lossThreshold: Double?
    }

    struct Quota: Codable {
        let enabled: Bool?
        let pinnedLeg: String?
        let hosts: [String]?
    }

    /// Транзит: через какие AS идёт путь до эндпоинта плеча. Появился 13.08.2026.
    struct ASN: Codable {
        let num: String
        let name: String?
        /// Страна, где реально стоят ХОПЫ этой AS, а не где она зарегистрирована.
        let cc: String?
    }

    struct PathChange: Codable {
        let time: Double?
        let asPath: String?
    }

    struct PathInfo: Codable {
        let host: String
        let leg: String
        let asns: [ASN]?
        let hops: Int?
        let changedAt: Double?
        let changes: [PathChange]?
        /// Зонд молчит — показывать обязательно, иначе это читается как «путь стабилен».
        let stale: Bool?
    }

    struct Payload: Codable {
        let segments: [Segment]?
        let hiddenSegments: [Segment]?
        let legs: [Leg]?
        let history: [Migration]?
        let failover: Failover?
        let quota: Quota?
        let paths: [PathInfo]?
        let error: String?
        let fetchedAt: Double?
    }
}
