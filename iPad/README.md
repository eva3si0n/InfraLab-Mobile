<p align="center">
  <img src="docs/screenshots/app-icon.png" width="120" alt="InfraLab icon">
</p>

<h1 align="center">InfraLab Mobile</h1>
<p align="center"><sub>iPadOS</sub></p>

<p align="center">
  A native iPadOS dashboard for your home-lab / self-hosted stack — Uptime Kuma, Grafana and a Homepage portal in one app, laid out for the big screen.
</p>

<p align="center">
  📱 iPhone: <a href="https://github.com/eva3si0n/InfraLab-Mobile-iOS">InfraLab-Mobile-iOS</a> ·
  🤖 Android: <a href="https://github.com/eva3si0n/InfraLab-Mobile-Android">InfraLab-Mobile-Android</a>
</p>

<p align="center"><a href="#по-русски">Русская версия ниже ↓</a></p>

---

## What it does

InfraLab is a lightweight SwiftUI client that pulls everything together on your iPad — a sidebar + detail split layout tuned for iPadOS:

- **Monitors** — your **Uptime Kuma** status page rendered natively: nodes are collapsible groups with an aggregate heartbeat rollup; tap one to expand every check (ping / port / DNS / push…) with its own Kuma-style heartbeat bar, latency and 24 h uptime.
- **VPN Cascade** — live state of a WireGuard egress cascade: per segment the active leg with health / role badges, WG throughput and per-leg RTT, a nested Kuma cascade monitor, egress-leg ping-from-home + monthly traffic vs cap, and a migration history.
- **Metrics** — a list of your **Grafana** dashboards; open one and every panel is drawn **natively** (Swift Charts for time series, plus stat / gauge / bar-gauge / table) straight from the dashboard's PromQL. No screenshots, no embedded web view.
- **HomePage** — your [gethomepage](https://gethomepage.dev) portal shown in an in-app web view.

Everything is read-only, dark-mode first, and refreshes on a timer or pull-to-refresh.

## How it talks to your services

| Section | Source | API |
|---|---|---|
| Monitors / VPN Cascade | Uptime Kuma | public status-page endpoints (`/api/status-page/<slug>`, `/api/status-page/heartbeat/<slug>`) |
| VPN Cascade / Metrics | Grafana → Prometheus | datasource proxy (`/api/datasources/proxy/uid/<ds>/api/v1/query[_range]`) |
| Metrics | Grafana | `/api/search`, `/api/dashboards/uid/<uid>` |
| HomePage | gethomepage | rendered in `WKWebView` |

No backend of its own — it just calls the services you already run. Point it at them over your LAN/VPN or a reverse proxy.

## Requirements

| | Minimum |
|---|---|
| iPadOS | 17.0 |
| Xcode | 16+ (developed with Xcode 26) |
| Tooling | [XcodeGen](https://github.com/yonaskolb/XcodeGen) |

## Build

The Xcode project is generated from `project.yml` (not committed):

```sh
brew install xcodegen
xcodegen generate
open InfraLabPad.xcodeproj
```

Set your own signing team in `project.yml` (`DEVELOPMENT_TEAM`), then build & run on a device.

## Configuration

All endpoints are entered in the **Settings** tab on first launch — nothing is hardcoded:

| Field | Example |
|---|---|
| Kuma Base URL | `https://kuma.example.com` |
| Kuma Status-page slug | `default` |
| Kuma API Key | *(optional — leave empty for public status pages)* |
| Grafana Base URL | `https://grafana.example.com` |
| Grafana Datasource UID | `prometheus` |
| Grafana Service-Account Token | *(Viewer/Editor token)* |
| HomePage URL | `https://home.example.com` |

Tokens are stored in the **Keychain**; plain settings live in `UserDefaults`. For native Grafana charts to work, the service-account token needs read access to the dashboards and their Prometheus datasource.

## Screenshots

### VPN Cascade

<p align="center"><img src="docs/screenshots/cascade.png" width="300" alt="VPN Cascade tab"></p>

## License

[MIT](LICENSE) © Ivan Serditykh

---

## По-русски

**InfraLab Mobile** (iPadOS) — нативный iPad-дашборд для домашней лаборатории / self-hosted стека: **Uptime Kuma**, **Grafana** и портал **Homepage** в одном приложении, с раскладкой «сайдбар + деталь» под большой экран. iPhone-версия: [InfraLab-Mobile-iOS](https://github.com/eva3si0n/InfraLab-Mobile-iOS), Android: [InfraLab-Mobile-Android](https://github.com/eva3si0n/InfraLab-Mobile-Android).

- **Monitors** — статус-страница **Uptime Kuma** нативно: узлы — сворачиваемые группы со сводной heartbeat-шкалой; тап разворачивает все проверки (ping / port / DNS / push…), у каждой своя Kuma-style шкала, задержка и аптайм за 24 ч.
- **VPN Cascade** — состояние каскада WireGuard-egress в реальном времени: по каждому сегменту активное плечо с бейджами здоровья/роли, throughput WG и RTT по плечам, вложенный Kuma-монитор каскада, пинг из дома + месячный трафик против лимита и история миграций.
- **Metrics** — список дашбордов **Grafana**; открываешь — панели рисуются **нативно** (Swift Charts для time series + stat / gauge / bar-gauge / table) прямо по PromQL. Без скриншотов и встроенного веба.
- **HomePage** — портал [gethomepage](https://gethomepage.dev) во встроенном web-view.

Только чтение, тёмная тема, обновление по таймеру или pull-to-refresh. Своего бэкенда нет — приложение обращается к сервисам, которые у тебя уже подняты (по LAN/VPN или через reverse-proxy).

**Требования:** iPadOS 17+, Xcode 16+, [XcodeGen](https://github.com/yonaskolb/XcodeGen).

**Сборка:** `brew install xcodegen` → `xcodegen generate` → открыть `InfraLabPad.xcodeproj`. Укажи свою команду подписи в `project.yml` (`DEVELOPMENT_TEAM`).

**Настройка:** все адреса вводятся во вкладке **Settings** при первом запуске — в коде ничего не зашито. Токены хранятся в **Keychain**.

**Лицензия:** [MIT](LICENSE) © Ivan Serditykh
