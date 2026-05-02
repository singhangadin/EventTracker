# Module eventtracker

EventTracker is a lightweight, modular Android library for capturing analytics events from a host
application and dispatching them reliably to one or more downstream destinations.

## Getting started

Initialize once in `Application.onCreate()`:

```kotlin
EventTracker.initialize(
    context = applicationContext,
    config = EventTrackerConfig.Builder()
        .addAdapter(BackendBatchAdapter(endpoint = "https://api.example.com/v1/events"))
        .addAdapter(FirebaseAdapter())
        .batchSize(50)
        .batchIntervalMs(30_000)
        .maxRetries(8)
        .build()
)
```

Then track events from anywhere:

```kotlin
EventTracker.track("checkout_started", mapOf("cart_size" to 3))
```

## Package structure

| Package | Contents |
|---------|----------|
| `in.singhangad.eventtracker` | Public API — `EventTracker`, `EventTrackerConfig`, `TrackEvent`, `Diagnostics` |
| `in.singhangad.eventtracker.adapter` | `EventAdapter` interface and built-in adapters |
