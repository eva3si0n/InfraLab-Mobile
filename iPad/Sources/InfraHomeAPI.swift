import Foundation

// Ответ InfraHome `GET /api/home` — тот же источник, что рисует веб-дашборд.
//
// Раньше раздел был просто web-view на gethomepage: внутри встроенного браузера
// Cloudflare Access просил логин, а на телефоне это неудобно. Теперь экран нативный
// и ходит по API с service token, как и каскад.
//
// ⚠️ Поля опциональны: дашборд наполняется конфигом (`config.json` сервиса), состав
// плиток и метрик меняется, и приложение не должно падать на незнакомой форме.

struct InfraHomeAPI {

    struct Metric: Codable {
        let label: String?
        let text: String?
        let value: Double?
        let ok: Bool?
        let group: String?
        let note: String?
        /// "ok" | "warn" | "crit" — цвет строки метрики.
        let state: String?
    }

    struct Tile: Codable {
        let name: String?
        let href: String?
        let description: String?
        let accent: String?
        let badge: String?
        let icon: String?
        let segment: String?
        /// "ok" | "warn" | "crit" — состояние плитки целиком.
        let status: String?
        let metrics: [Metric]?
    }

    struct Segment: Codable {
        let name: String?
        let label: String?
        let accent: String?
    }

    struct Group: Codable {
        let name: String?
        let framed: Bool?
        /// Свёрнута ли группа на вебе — уважаем то же значение, чтобы длинный
        /// дашборд не разворачивался целиком на телефоне.
        let collapsed: Bool?
        let segments: [Segment]?
        let tiles: [Tile]?
    }

    struct Kuma: Codable {
        let up: Int?
        let down: Int?
        let pending: Int?
        let maintenance: Int?
        let total: Int?
        let names: [String]?
    }

    struct Alert: Codable {
        let name: String?
        let severity: String?
        let state: String?
        let summary: String?
    }

    struct Alerts: Codable {
        let firing: Int?
        let pending: Int?
        let list: [Alert]?
    }

    struct Payload: Codable {
        let generatedAt: Double?
        let title: String?
        let refreshSeconds: Int?
        let groups: [Group]?
        let kuma: Kuma?
        let alerts: Alerts?
    }
}
