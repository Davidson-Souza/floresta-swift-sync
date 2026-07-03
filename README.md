# Floresta Swift Sync

Floresta Swift Sync is a minimal Android app that runs a Floresta node in a foreground service and shows sync progress from the local JSON-RPC and Prometheus metrics endpoints.

The app currently supports Bitcoin mainnet and Signet. On first launch, it asks for the network and optionally lets you choose a custom UTXO hints server. Before starting Floresta, it downloads the selected network's hints file into the app-private `.floresta` data directory.

## Features

- Foreground Floresta service for background syncing.
- First-run network selector for Bitcoin or Signet.
- Optional custom hints server URL.
- Hints download progress before daemon startup.
- IBD progress, elapsed time, ETA, and blocks/s metrics.
- Uses Floresta JSON-RPC on `127.0.0.1:8332`.
- Uses Floresta metrics endpoint on `127.0.0.1:3333`.
- Keeps the screen and service awake while syncing.

## Prerequisites

- Android SDK installed.
- JDK 17.
- The Floresta Android Maven package published to your local Maven repository.

Before building this app, build and publish the Floresta Android package from:

https://github.com/getfloresta/floresta-ffi/tree/master/floresta-android

For example:

```bash
git clone https://github.com/getfloresta/floresta-ffi.git
cd floresta-ffi/floresta-android
./gradlew publishToMavenLocal
```

This app resolves `org.getfloresta:floresta-android:0.1.0-SNAPSHOT` from `mavenLocal()`.

## Build

From this repository root:

```bash
./gradlew assembleDebug
```

The debug APK will be written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Runtime Notes

- Bitcoin hints are downloaded from `{hints_server}/bitcoin` and stored as `.floresta/bitcoin.hints`.
- Signet hints are downloaded from `{hints_server}/signet` and stored as `.floresta/signet.hints`.
- The default hints server is `https://utxohints.store/hints`.
- The app keeps JSON-RPC, Electrum, and metrics ports unchanged across networks.
