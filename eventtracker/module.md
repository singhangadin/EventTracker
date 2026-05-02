# Module eventtracker

EventTracker is a lightweight, modular Android library for capturing analytics events from a host
application and dispatching them reliably to one or more downstream destinations.

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

The public API is centred on [EventTracker], the singleton entry point. Configuration is built with
[EventTrackerConfig.Builder]. Destinations implement [in.singhangad.eventtracker.adapter.EventAdapter];
built-in options are [in.singhangad.eventtracker.adapter.BackendBatchAdapter] for batched HTTP
delivery and [in.singhangad.eventtracker.adapter.LoggingAdapter] for Logcat output during development.
Runtime counters are exposed via [Diagnostics].

The `in.singhangad.eventtracker` package contains the public API. Adapter implementations live in
`in.singhangad.eventtracker.adapter`. Internal implementation details are excluded from this
documentation.
