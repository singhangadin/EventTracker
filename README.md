# EventTracker

A pluggable, batched, retryable event-tracking SDK for Android. Events are persisted to a local Room database before any network attempt, delivered in configurable batches with truncated exponential backoff, and routed to one or more adapter destinations simultaneously.

[![](https://jitpack.io/v/singhangadin/EventTracker.svg)](https://jitpack.io/#singhangadin/EventTracker)
![minSdk](https://img.shields.io/badge/minSdk-23-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-blue)

---

## Features

- **Offline-first** — events survive process death; the database is flushed on the next launch
- **Batched HTTP delivery** — configurable batch size and interval with gzip compression
- **Retry with full jitter** — truncated exponential backoff spreads retries to avoid thundering-herd
- **Dead-letter queue** — exhausted retries move to a DLQ that can be replayed or purged
- **Pluggable adapters** — ship to your own backend, Firebase Analytics, and Logcat simultaneously
- **Per-event sampling** — drop a fraction of high-volume events before they reach the network
- **GDPR/opt-out** — `setOptOut` blocks all tracking; `wipeLocalData` erases locally stored events
- **Session management** — automatic session rotation when the app returns to foreground
- **Diagnostics** — in-process counters for tracked, delivered, dropped, retrying, and dead-lettered events

---

## Installation

Add JitPack to your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}
```

Then add the dependencies you need:

```kotlin
// Core library (always required)
implementation("com.github.singhangadin.EventTracker:eventtracker:1.0.0")

// Firebase Analytics adapter (optional)
implementation("com.github.singhangadin.EventTracker:eventtracker-adapter-firebase:1.0.0")
```

**Requirements:** minSdk 23 · compileSdk 34 · Kotlin 2.x

---

## Quick Start

Initialize once in `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        EventTracker.initialize(
            context = applicationContext,
            config = EventTrackerConfig.Builder()
                .addAdapter(BackendBatchAdapter("https://api.example.com/v1/events"))
                .addAdapter(FirebaseAdapter())
                .addAdapter(LoggingAdapter()) // auto-disabled in release builds
                .batchSize(50)
                .batchIntervalMs(30_000)
                .maxRetries(8)
                .logger(AndroidLogger())
                .build()
        )
    }
}
```

Track events from anywhere:

```kotlin
EventTracker.track("checkout_started", mapOf(
    "cart_size" to 3,
    "currency" to "USD",
    "total" to 49.99,
))

EventTracker.identify("user_42", mapOf("plan" to "pro"))
```

---

## Configuration

All options are set on `EventTrackerConfig.Builder`:

| Method | Default | Description |
|---|---|---|
| `addAdapter(adapter)` | — | Register a destination. At least one required. |
| `batchSize(n)` | `50` | Max events per HTTP batch. Clamped to 1–1000. |
| `batchIntervalMs(ms)` | `30_000` | Max queue age before an automatic flush. Min 1,000 ms. |
| `maxRetries(n)` | `8` | HTTP attempts before an event is dead-lettered. Min 1. |
| `maxLocalEvents(n)` | `10_000` | Hard cap on the local events table. Oldest rows dropped on overflow. Min 100. |
| `samplingRate(name, rate)` | `1.0` | Fraction of named events to keep. `0.0` drops all; `1.0` keeps all. |
| `encryptAtRest(bool)` | `false` | Encrypt the database with SQLCipher (requires host app dependency). |
| `logger(logger)` | no-op | Provide an `EventLogger` implementation for internal diagnostics. |

---

## Public API

### Tracking

```kotlin
// Track an event
EventTracker.track(
    name = "page_view",                         // 1–128 chars, [a-zA-Z0-9_]
    properties = mapOf("screen" to "home"),     // primitives, lists, nested maps
    destinations = setOf("backend"),            // null = all adapters
)

// Identify a user
EventTracker.identify(userId = "user_42", traits = mapOf("email" to "user@example.com"))

// Clear user identity and start a new anonymous session
EventTracker.reset()
```

### Delivery control

```kotlin
// Force an immediate flush (e.g. before the app backgrounds)
EventTracker.flush().join()

// Opt out — silently drops all subsequent track/identify calls
EventTracker.setOptOut(true)

// Delete all locally stored events (GDPR erasure)
EventTracker.wipeLocalData().join()
```

### Dead-letter queue

Events that exhaust all retry attempts are moved to a dead-letter queue (DLQ). Common causes are HTTP 400 (bad payload) or a server that was down for longer than the retry window.

```kotlin
// How many events are in the DLQ?
val count = EventTracker.deadLetterSize()

// Move up to 500 DLQ events back into the live queue (e.g. after a backend fix)
val requeued = EventTracker.replayDeadLetters(limit = 500)

// Permanently discard all DLQ events
EventTracker.purgeDeadLetters()
```

### Diagnostics

```kotlin
val d = EventTracker.diagnostics()
// d.tracked      — total accepted by track()
// d.dropped      — rejected by validation, sampling, or opt-out
// d.persisted    — written to the local database
// d.delivered    — successfully delivered to at least one adapter
// d.retrying     — currently waiting for a retry
// d.deadLettered — moved to DLQ
// d.queueDepth   — current un-delivered rows in the events table
```

---

## Adapters

### BackendBatchAdapter

Delivers events to a first-party HTTP endpoint as batched JSON with gzip compression.

```kotlin
BackendBatchAdapter(
    endpoint = "https://api.example.com/v1/events",
    authToken = "Bearer <token>",   // optional, sent as Authorization header
)
```

**Batch payload shape:**

```json
{
  "schema_version": 1,
  "sent_at": 1714000000000,
  "device": { "os": "android", "os_version": "14", "model": "Pixel 7", "app_version": "2.1.0", "locale": "en-US" },
  "events": [
    {
      "id": "uuid",
      "name": "checkout_started",
      "properties": { "cart_size": 3 },
      "user_id": "user_42",
      "session_id": "abc123",
      "client_ts": 1714000000000,
      "client_uptime_ms": 60000,
      "attempt_count": 0
    }
  ]
}
```

**HTTP status handling:**

| Status | Behaviour |
|---|---|
| 2xx | Success — events deleted from queue |
| 400 | Permanent failure — events moved to DLQ immediately |
| 429 | Retryable — batch size halved for next attempt; honours `Retry-After` header |
| 5xx | Retryable — exponential backoff |
| Network error | Retryable — exponential backoff |

### FirebaseAdapter

Wraps `FirebaseAnalytics.logEvent`. Requires the `:eventtracker-adapter-firebase` module.

```kotlin
// No constructor arguments needed — picks up the app's google-services.json automatically
FirebaseAdapter()
```

Property values are mapped to Bundle types automatically. Event names and property keys are truncated to Firebase's platform limits (40 and 24 characters respectively).

### LoggingAdapter

Prints every event to Logcat. `accepts()` returns `false` in release builds, so it produces zero overhead in production.

```kotlin
LoggingAdapter()
```

### Custom adapters

Implement `EventAdapter` to send events anywhere:

```kotlin
class MixpanelAdapter : EventAdapter {
    override val id = "mixpanel"

    override fun initialize(context: Context, logger: EventLogger) { /* init Mixpanel SDK */ }

    override fun accepts(event: TrackEvent): Boolean =
        event.destinations?.contains(id) ?: true

    override suspend fun deliver(event: TrackEvent): DeliveryOutcome = try {
        mixpanel.track(event.name, event.properties)
        DeliveryOutcome.Success
    } catch (t: Throwable) {
        DeliveryOutcome.RetryableFailure(t)
    }
}
```

`DeliveryOutcome` variants:

| Outcome | Meaning |
|---|---|
| `Success` | Event delivered; counters incremented |
| `RetryableFailure(cause)` | Transient error; dispatcher will retry |
| `PermanentFailure(cause)` | Non-retryable; event goes to DLQ |

---

## Architecture

```
Application
    │
    ▼
EventTracker (singleton)
    │
    ├── EventDispatcher (single-threaded CoroutineScope)
    │       ├── SamplingFilter        — probabilistic drop before persistence
    │       ├── Room EventDatabase    — WAL-mode SQLite, two tables
    │       │       ├── events        — live queue (QUEUED → SENDING → deleted)
    │       │       └── dead_letter_events — exhausted-retry graveyard
    │       └── EventAdapter[]        — fan-out delivery
    │
    ├── FlushScheduler (WorkManager periodic + lifecycle observer)
    │       └── triggers dispatcher.flush() on interval and app foreground
    │
    └── OptOutGuard                  — SharedPreferences-backed opt-out flag
```

**Retry policy:** `delay(n) = random(0, min(maxDelayMs, baseDelayMs × 2ⁿ))` — full jitter eliminates synchronized retry waves across devices. The server's `Retry-After` header is respected when present.

---

## Logging

The default logger is a no-op. To see internal logs during development:

```kotlin
// Built-in Android logger
.logger(AndroidLogger())

// Or implement your own
.logger(object : EventLogger {
    override fun info(tag: String, message: String) { Timber.tag(tag).i(message) }
    override fun error(tag: String, message: String, throwable: Throwable?) { Timber.tag(tag).e(throwable, message) }
    // ... other levels
})
```

---

## Event name rules

Event names must match `[a-zA-Z0-9_]{1,128}`. Names outside this pattern are rejected silently and increment `Diagnostics.dropped`.

---

## Routing events to specific adapters

Pass a `destinations` set to send an event to only a subset of adapters:

```kotlin
// Only the backend adapter receives this event
EventTracker.track("debug_ping", destinations = setOf("backend"))

// Both backend and Firebase receive this event; LoggingAdapter is skipped
EventTracker.track("purchase", mapOf("amount" to 9.99), destinations = setOf("backend", "firebase"))
```

Adapter IDs: `"backend"`, `"firebase"`, `"log"`.

---

## Sampling

Drop a fraction of high-volume events to reduce costs without losing signal:

```kotlin
EventTrackerConfig.Builder()
    .addAdapter(...)
    .samplingRate("scroll_depth", 0.1)   // keep 10 % of scroll_depth events
    .samplingRate("heartbeat", 0.01)     // keep 1 % of heartbeat events
    .build()
```

Events not listed in `samplingRates` are always kept.

---

## API docs

Versioned Dokka HTML documentation is published to GitHub Pages on every release:

- **Latest:** `https://singhangadin.github.io/EventTracker/latest/`
- **By version:** `https://singhangadin.github.io/EventTracker/<version>/`

---

## License

```
Copyright 2026 Angad Singh

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```
