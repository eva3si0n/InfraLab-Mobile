<p align="center">
  <img src="iOS/docs/screenshots/app-icon.png" width="120" alt="InfraLab icon">
</p>

<h1 align="center">InfraLab Mobile</h1>

<p align="center">
  Native dashboards for a home-lab / self-hosted stack — Uptime Kuma, Grafana, a VPN-cascade view and a Homepage portal — on iPhone, iPad and Android.
</p>

<p align="center"><a href="#по-русски">Русская версия ниже ↓</a></p>

---

## Platforms

| Platform | Directory | Stack |
|---|---|---|
| iOS (iPhone) | [iOS/](iOS) | SwiftUI + XcodeGen |
| iPadOS | [iPad/](iPad) | SwiftUI + XcodeGen (sidebar + detail layout) |
| Android | [Android/](Android) | Jetpack Compose (Material You) |

Each platform folder has its own `README` with build & configuration details.

## What it does

- **Monitors** — your **Uptime Kuma** status page rendered natively (collapsible node groups, per-check heartbeat bars, 24 h uptime).
- **VPN Cascade** — live state of a WireGuard egress cascade: per segment the active leg with Healthy/Primary badges, WG throughput, per-leg RTT, a nested Kuma monitor, ping-from-home + monthly traffic vs cap, and a migration history.
- **Metrics** — your **Grafana** dashboards drawn **natively** from their PromQL (charts + stat / gauge / bar-gauge / table) via the datasource proxy.
- **HomePage** — your [gethomepage](https://gethomepage.dev) portal in an in-app web view.

Everything is read-only, dark-mode first, and refreshes on a timer or pull-to-refresh. No backend of its own — it just calls the services you already run, over your LAN/VPN or a reverse proxy.

## Configuration & privacy

Endpoints are entered in the app's **Settings** on first launch — nothing is hardcoded. Real infra names / URLs / tokens live only in each platform's **gitignored `seed.json`** (baked into personal builds); the committed `seed.example.json` carries placeholders. Tokens are stored in the Keychain (iOS/iPad) / EncryptedSharedPreferences (Android).

## License

[MIT](LICENSE) © Ivan Serditykh

---

## По-русски

**InfraLab Mobile** — нативные дашборды домашней лаборатории / self-hosted стека (**Uptime Kuma**, **Grafana**, состояние **VPN-каскада** и портал **Homepage**) для iPhone, iPad и Android. Один репозиторий, папка на платформу:

| Платформа | Папка | Стек |
|---|---|---|
| iOS (iPhone) | [iOS/](iOS) | SwiftUI + XcodeGen |
| iPadOS | [iPad/](iPad) | SwiftUI + XcodeGen (сайдбар + деталь) |
| Android | [Android/](Android) | Jetpack Compose (Material You) |

В каждой папке — свой `README` со сборкой и настройкой.

- **Monitors** — статус-страница Uptime Kuma нативно.
- **VPN Cascade** — каскад WireGuard-egress: активное плечо + бейджи Healthy/Primary, throughput, RTT по плечам, вложенный Kuma-монитор, пинг из дома + месячный трафик, история миграций.
- **Metrics** — дашборды Grafana рисуются нативно по PromQL.
- **HomePage** — портал gethomepage во встроенном web-view.

Только чтение, тёмная тема, обновление по таймеру/pull-to-refresh. Своего бэкенда нет. Реальные адреса/токены — только в gitignored `seed.json` каждой платформы (в публичном коде плейсхолдеры).

**Лицензия:** [MIT](LICENSE) © Ivan Serditykh
