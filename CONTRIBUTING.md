# Contributing

Thanks for your interest in DSH Mobile!

## Setup

- Android Studio (Koala or newer) with JDK 17+ and Android SDK 35.
- `./gradlew :app:assembleDebug` builds the debug APK.

## Development against a real harness

1. Run the DeepSeek Harness: `dsh web` on your computer (default port 3080).
2. Device/emulator via USB: `adb reverse tcp:3080 tcp:3080`, then pick
   **This phone** in the app (it probes `127.0.0.1:3080`). Or run the harness
   in Termux on the device itself — see `harness/TERMUX.md`.
3. Wi-Fi LAN mode: apply `harness/cordis.patch.lan.yml` as described in
   `harness/README.md`.

## Repository layout

- `core/` — pure JVM: wire protocol (DTOs, RPC client, WebSocket downlinks,
  reconnect loop), session event folding, notification classifier.
- `app/` — Android UI: Compose screens, connection/discovery, foreground
  service, notifications, i18n.
- `mock-harness/` — Ktor mock of the harness `/api` server for tests.
- `tools/capture/` — Node script that records real harness traffic into JSON
  fixtures for conformance tests.

## Conventions

- Kotlin official code style; one file per screen/component where sensible.
- The wire layer parses leniently (unknown keys/events/tool cards must fall
  back, never crash).
- UI strings live in `values*/strings.xml` only — never hardcode.
- Pull requests need CI green (unit tests + lint + assemble).

## Release

Tag `v*` → the Release workflow builds and publishes a signed APK (when
signing secrets are configured) or an unsigned APK.

For a local release that a phone on the same network can install without a
cable, also publish it to the download page served on port 8080:

```sh
DSH_VERSION_NAME=0.22.5 ./gradlew :app:assembleRelease
node scripts/publish-apk.mjs --version 0.22.5 --note "本次改动：…" --verify
```

The script copies the APK to `$DSH_APK_DIR` (default `../PiDeck-Mobile/apk`),
merges `SHA256SUMS.txt` without dropping other projects' entries, and rewrites
the DSH Mobile block of `index.html`; rollback links are generated from the
APKs that actually exist in that directory. `--verify` re-reads the result over
HTTP (status, byte count, checksum list, landing page).
