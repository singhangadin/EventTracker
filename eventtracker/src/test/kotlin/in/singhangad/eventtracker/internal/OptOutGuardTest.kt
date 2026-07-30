package `in`.singhangad.eventtracker.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OptOutGuardTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("eventtracker_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `defaults to not opted out`() {
        assertFalse(OptOutGuard(context).isOptedOut)
    }

    @Test
    fun `setOptOut true is reflected immediately`() {
        val guard = OptOutGuard(context)
        guard.setOptOut(true)
        assertTrue(guard.isOptedOut)
    }

    @Test
    fun `setOptOut false clears the flag`() {
        val guard = OptOutGuard(context)
        guard.setOptOut(true)
        guard.setOptOut(false)
        assertFalse(guard.isOptedOut)
    }

    @Test
    fun `opt-out flag survives a new guard instance (persistence)`() {
        OptOutGuard(context).setOptOut(true)
        // A brand-new guard reads the persisted value from SharedPreferences.
        assertTrue(OptOutGuard(context).isOptedOut)
    }
}
