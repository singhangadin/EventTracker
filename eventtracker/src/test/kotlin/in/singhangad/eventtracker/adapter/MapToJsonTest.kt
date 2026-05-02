package `in`.singhangad.eventtracker.adapter

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MapToJsonTest {

    @Test
    fun `empty map produces empty JSON object`() {
        assertEquals("{}", mapToJson(emptyMap()))
    }

    @Test
    fun `null value serialized as JSON null`() {
        val obj = JSONObject(mapToJson(mapOf("key" to null)))
        assertTrue(obj.isNull("key"))
    }

    @Test
    fun `String value preserved`() {
        val obj = JSONObject(mapToJson(mapOf("s" to "hello")))
        assertEquals("hello", obj.getString("s"))
    }

    @Test
    fun `Int value preserved`() {
        val obj = JSONObject(mapToJson(mapOf("i" to 42)))
        assertEquals(42, obj.getInt("i"))
    }

    @Test
    fun `Long value preserved`() {
        val obj = JSONObject(mapToJson(mapOf("l" to 999L)))
        assertEquals(999L, obj.getLong("l"))
    }

    @Test
    fun `Double value preserved`() {
        val obj = JSONObject(mapToJson(mapOf("d" to 3.14)))
        assertEquals(3.14, obj.getDouble("d"), 0.001)
    }

    @Test
    fun `Float value converted to Double`() {
        val obj = JSONObject(mapToJson(mapOf("f" to 1.5f)))
        assertEquals(1.5, obj.getDouble("f"), 0.001)
    }

    @Test
    fun `Boolean value preserved`() {
        val obj = JSONObject(mapToJson(mapOf("b" to true)))
        assertTrue(obj.getBoolean("b"))
    }

    @Test
    fun `List serialized as JSON array`() {
        val obj = JSONObject(mapToJson(mapOf("items" to listOf("x", "y", "z"))))
        val arr = obj.getJSONArray("items")
        assertEquals(3, arr.length())
        assertEquals("x", arr.getString(0))
        assertEquals("z", arr.getString(2))
    }

    @Test
    fun `Map serialized as nested JSON object`() {
        val obj = JSONObject(mapToJson(mapOf("nested" to mapOf("a" to "1", "b" to "2"))))
        val inner = obj.getJSONObject("nested")
        assertEquals("1", inner.getString("a"))
        assertEquals("2", inner.getString("b"))
    }

    @Test
    fun `unknown type falls back to toString`() {
        data class Custom(val x: Int)
        val obj = JSONObject(mapToJson(mapOf("c" to Custom(7))))
        assertEquals("Custom(x=7)", obj.getString("c"))
    }

    @Test
    fun `multiple keys round-trip correctly`() {
        val input = mapOf(
            "name" to "page_view",
            "count" to 3,
            "active" to false,
        )
        val obj = JSONObject(mapToJson(input))
        assertEquals("page_view", obj.getString("name"))
        assertEquals(3, obj.getInt("count"))
        assertFalse(obj.getBoolean("active"))
    }
}
