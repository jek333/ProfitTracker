# SQ5 Monitor

An Android app for monitoring and diagnosing a **2018 Audi SQ5** (FY, 3.0 TFSI) through an
inexpensive ELM327-compatible OBD-II adapter. It covers the monitoring side of OBDeleven/VCDS:
live gauges, a VAG-style auto-scan of every control module's fault memory, and alerts while you drive.

## Features

| | |
|---|---|
| **Dashboard** | Configurable gauges: boost (psi/kPa), RPM, speed, coolant, oil and intake temperatures, battery voltage, throttle, load, timing, fuel level, MAF-based fuel economy, and more. Check-engine-light status and refresh rate are shown at the top. |
| **Live data** | Every sensor the car supports, with session min/max and a sparkline. Star a sensor to add it to the dashboard. |
| **Alerts** | Overheating coolant/oil, heat-soaked intake air, alternator not charging, weak battery, check engine light. Thresholds are adjustable, and alerts arrive as notifications while the app is in the background. |
| **Diagnose: Engine (OBD-II)** | Stored, pending and permanent codes from the engine and transmission, with descriptions, plus emissions readiness monitors (useful before an inspection). Codes can be cleared after a confirmation. |
| **Diagnose: All modules** | Auto-scan of the gateway-reachable modules (engine, gearbox, ABS, airbag, cluster, gateway, HVAC, central electrics, steering, MMI, ACC, park assist, doors) over UDS. Shows part numbers and software versions, decodes each fault as `P0456 00` with active/stored status, and can clear one module's faults. Produces a shareable text report. |
| **Vehicle** | VIN decode (model, model year, check digit), adapter firmware and protocol, a read-only UDS identifier reader for any module, and a raw adapter log for troubleshooting. |
| **Drive logs** | Record the dashboard gauges to CSV and share them to a spreadsheet or a log viewer such as Datazap or MegaLogViewer. |
| **Demo mode** | A built-in simulated SQ5 (idle, full-throttle pull, cruise) with stored faults, so every screen can be tried without a car. |

### Deliberately not included

OBDeleven-style **coding, adaptations and "one-click apps"** (e.g. needle sweep, disabling start/stop)
are not implemented. They require security access and write services, and a write interrupted by a
cheap Bluetooth adapter can leave a module unconfigured. The app is read-only apart from clearing fault codes.

## Hardware

OBDeleven's own dongle uses a proprietary protocol and **will not work** with this app. Use any
ELM327-compatible adapter:

- **Bluetooth Classic** (recommended): OBDLink LX/MX+, vLinker MC/MC+, Veepeak Mini. Pair it in
  Android's Bluetooth settings first (PIN usually `1234` or `0000`).
- **Bluetooth LE**: Vgate iCar Pro BLE, Veepeak OBDCheck BLE, OBDLink CX. No pairing needed; tap **Scan**.
- **Wi-Fi**: join the adapter's network, then connect to `192.168.0.10:35000` (the default).

Genuine-chip adapters (OBDLink, vLinker) are much faster and more reliable than cheap clones,
especially for module scans, which need the `ATFCSH` flow-control commands that some v1.5 clones lack.

The 2018 SQ5 predates Audi's SFD gateway lock (2020+), so all reads work without unlocking.

## Using it

1. Plug the adapter into the OBD-II port under the dashboard on the driver's side and switch the ignition on.
2. Open the app, pick the adapter (Bluetooth, Wi-Fi or Demo) and tap it to connect.
3. You land on the dashboard. Tap the pencil icon to choose gauges, or the record button to log a drive.
4. Use **Diagnose → All modules → Scan all modules** for a full fault scan (about a minute; the engine can be off).

## Getting the APK

Every push that touches `sq5-monitor/` builds the app on GitHub Actions
(workflow **SQ5 Monitor (Android)**). Open the run and download the `sq5-monitor-apk` artifact. The
release APK is signed with the debug key so it can be sideloaded directly (allow "Install unknown apps"
for your browser or file manager). Android 8.0 or newer is required.

To build locally you need JDK 17 and the Android SDK (API 35):

```bash
cd sq5-monitor
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew -p core test                  # protocol tests; no Android SDK needed
./gradlew :app:testDebugUnitTest :app:lintDebug
```

## Architecture

```
sq5-monitor/
├── core/   Pure Kotlin/JVM, no Android dependencies (included build, testable anywhere)
│   ├── link/     ElmLink byte pipe, TCP link, QueueInputStream
│   ├── elm/      ELM327 driver: command/response, init sequence, error handling
│   ├── isotp/    CAN frame parsing and ISO 15765-2 reassembly (multi-ECU, multi-frame)
│   ├── obd/      SAE J1979 PIDs, units, gauge signals, DTCs and descriptions, readiness
│   ├── uds/      ISO 14229 client, VAG module table, auto-scanner, report builder
│   ├── monitor/  Live polling loop (fast/slow signals, multi-PID requests), alerts, statistics
│   ├── vehicle/  VIN decoding
│   ├── log/      CSV drive log writer
│   └── sim/      Simulated ELM327 + SQ5 used by demo mode and the integration tests
└── app/    Android app (Kotlin, Jetpack Compose, Material 3)
    ├── connection/  Bluetooth Classic/LE transports, ConnectionManager, LiveDataHub, DriveRecorder, foreground service
    ├── data/        DataStore settings, drive log files
    └── ui/          Screens and ViewModels (connect, dashboard, live, diagnose, vehicle, settings, logs)
```

Key design points:

- **One adapter, many users.** `Elm327` serialises every exchange with a mutex. `UdsClient.withModule`
  holds the adapter while it switches CAN headers to a module and restores functional (7DF) addressing
  afterwards, so live polling and module scans can interleave safely.
- **One poller.** Screens declare the signals they need (`LiveDataHub.demand`) and the hub polls the
  union, plus a small watchdog set for alerts. Up to six PIDs go in each request, and slow signals
  (temperatures, fuel, battery) refresh every fifth cycle so boost and RPM stay responsive.
- **Headers on.** The adapter runs with `ATH1` and the app reassembles ISO-TP itself. This keeps replies
  from different ECUs apart and works the same for OBD-II and UDS.
- **Testable without a car.** `SimulatedElm327` implements the AT commands and OBD/UDS services the app
  uses, with real ISO-TP framing. The integration tests drive the full stack against it.

## Disclaimer

Not affiliated with Audi AG or OBDeleven. Use at your own risk, and don't operate the app while driving.
