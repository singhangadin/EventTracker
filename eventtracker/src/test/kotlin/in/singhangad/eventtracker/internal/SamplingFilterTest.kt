package `in`.singhangad.eventtracker.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingFilterTest {

    @Test
    fun `rate 0 drops all events`() {
        val filter = SamplingFilter(mapOf("click" to 0.0))
        repeat(200) {
            assertFalse("rate=0 must always drop", filter.shouldKeep("click"))
        }
    }

    @Test
    fun `rate 1 keeps all events`() {
        val filter = SamplingFilter(mapOf("purchase" to 1.0))
        repeat(200) {
            assertTrue("rate=1 must always keep", filter.shouldKeep("purchase"))
        }
    }

    @Test
    fun `unknown event name defaults to keep`() {
        val filter = SamplingFilter(emptyMap())
        repeat(50) {
            assertTrue(filter.shouldKeep("any_event"))
        }
    }

    @Test
    fun `filter is scoped to the named event only`() {
        val filter = SamplingFilter(mapOf("drop_me" to 0.0))
        repeat(100) { assertFalse(filter.shouldKeep("drop_me")) }
        repeat(100) { assertTrue(filter.shouldKeep("keep_me")) }
    }

    @Test
    fun `multiple event rates are independent`() {
        val filter = SamplingFilter(mapOf(
            "always_drop" to 0.0,
            "always_keep" to 1.0,
        ))
        repeat(100) {
            assertFalse(filter.shouldKeep("always_drop"))
            assertTrue(filter.shouldKeep("always_keep"))
        }
    }
}
