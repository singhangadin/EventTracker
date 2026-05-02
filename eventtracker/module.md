# Module eventtracker

EventTracker is a lightweight, modular Android library for capturing analytics events from a host
application and dispatching them reliably to one or more downstream destinations.

Initialize once in `Application.onCreate()`, then call `EventTracker.track()` from anywhere in the app.

The public API lives in the `in.singhangad.eventtracker` package. Adapter implementations are
in `in.singhangad.eventtracker.adapter`. Internal implementation details are excluded from this
documentation.
