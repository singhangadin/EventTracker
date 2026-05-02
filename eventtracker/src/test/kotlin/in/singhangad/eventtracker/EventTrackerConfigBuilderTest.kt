package `in`.singhangad.eventtracker

import `in`.singhangad.eventtracker.adapter.BackendBatchAdapter
import org.junit.Assert.*
import org.junit.Test

class EventTrackerConfigBuilderTest {

    private fun adapterA() = BackendBatchAdapter("https://a.example.com")
    private fun adapterB() = BackendBatchAdapter("https://b.example.com")

    @Test(expected = IllegalStateException::class)
    fun `build without adapters throws`() {
        EventTrackerConfig.Builder().build()
    }

    @Test
    fun `default values match constants`() {
        val config = EventTrackerConfig.Builder().addAdapter(adapterA()).build()

        assertEquals(EventTrackerConfig.DEFAULT_BATCH_SIZE, config.batchSize)
        assertEquals(EventTrackerConfig.DEFAULT_BATCH_INTERVAL_MS, config.batchIntervalMs)
        assertEquals(EventTrackerConfig.DEFAULT_MAX_RETRIES, config.maxRetries)
        assertEquals(EventTrackerConfig.DEFAULT_MAX_LOCAL_EVENTS, config.maxLocalEvents)
        assertFalse(config.encryptAtRest)
        assertTrue(config.samplingRates.isEmpty())
    }

    @Test
    fun `batchSize is clamped to 1 at the low end`() {
        val config = EventTrackerConfig.Builder().addAdapter(adapterA()).batchSize(0).build()
        assertEquals(1, config.batchSize)
    }

    @Test
    fun `batchSize is clamped to 1000 at the high end`() {
        val config = EventTrackerConfig.Builder().addAdapter(adapterA()).batchSize(9999).build()
        assertEquals(1000, config.batchSize)
    }

    @Test
    fun `batchSize within range is preserved`() {
        val config = EventTrackerConfig.Builder().addAdapter(adapterA()).batchSize(75).build()
        assertEquals(75, config.batchSize)
    }

    @Test
    fun `batchIntervalMs enforces 1000ms minimum`() {
        val config = EventTrackerConfig.Builder().addAdapter(adapterA()).batchIntervalMs(50).build()
        assertEquals(1_000L, config.batchIntervalMs)
    }

    @Test
    fun `batchIntervalMs above minimum is preserved`() {
        val config = EventTrackerConfig.Builder().addAdapter(adapterA()).batchIntervalMs(60_000L).build()
        assertEquals(60_000L, config.batchIntervalMs)
    }

    @Test
    fun `maxRetries enforces minimum of 1`() {
        val config = EventTrackerConfig.Builder().addAdapter(adapterA()).maxRetries(0).build()
        assertEquals(1, config.maxRetries)
    }

    @Test
    fun `maxRetries above minimum is preserved`() {
        val config = EventTrackerConfig.Builder().addAdapter(adapterA()).maxRetries(12).build()
        assertEquals(12, config.maxRetries)
    }

    @Test
    fun `maxLocalEvents enforces minimum of 100`() {
        val config = EventTrackerConfig.Builder().addAdapter(adapterA()).maxLocalEvents(10).build()
        assertEquals(100, config.maxLocalEvents)
    }

    @Test
    fun `samplingRate is clamped to 0 and 1`() {
        val config = EventTrackerConfig.Builder()
            .addAdapter(adapterA())
            .samplingRate("too_high", 2.0)
            .samplingRate("too_low", -0.5)
            .samplingRate("valid", 0.3)
            .build()

        assertEquals(1.0, config.samplingRates["too_high"]!!, 0.001)
        assertEquals(0.0, config.samplingRates["too_low"]!!, 0.001)
        assertEquals(0.3, config.samplingRates["valid"]!!, 0.001)
    }

    @Test
    fun `multiple adapters are preserved in registration order`() {
        val a = adapterA()
        val b = adapterB()
        val config = EventTrackerConfig.Builder().addAdapter(a).addAdapter(b).build()

        assertEquals(2, config.adapters.size)
        assertSame(a, config.adapters[0])
        assertSame(b, config.adapters[1])
    }

    @Test
    fun `encryptAtRest flag is forwarded`() {
        val config = EventTrackerConfig.Builder()
            .addAdapter(adapterA())
            .encryptAtRest(true)
            .build()
        assertTrue(config.encryptAtRest)
    }
}
