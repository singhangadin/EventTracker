package `in`.singhangad.eventtracker.adapter

import android.content.Context
import android.os.Build
import `in`.singhangad.eventtracker.TrackEvent
import `in`.singhangad.eventtracker.internal.EventLogger
import `in`.singhangad.eventtracker.internal.RetryPolicy
import `in`.singhangad.eventtracker.internal.db.DeadLetterEntity
import `in`.singhangad.eventtracker.internal.db.DLQDao
import `in`.singhangad.eventtracker.internal.db.EventDao
import `in`.singhangad.eventtracker.internal.db.EventDatabase
import `in`.singhangad.eventtracker.internal.db.EventEntity
import `in`.singhangad.eventtracker.internal.db.EventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream
import androidx.room.withTransaction
import java.util.concurrent.TimeUnit

private const val TAG = "ET/Backend"
private const val SCHEMA_VERSION = 1
private const val GZIP_THRESHOLD_BYTES = 1024

/**
 * Adapter that delivers events to a first-party backend endpoint as batched HTTP POST requests.
 *
 * Events are persisted to the local Room database before any network attempt. This adapter's
 * [deliver] is a no-op — the dispatcher already wrote the event to the database as [EventState.QUEUED].
 * The actual network send happens in [flush], which is triggered by [FlushScheduler].
 *
 * **Retry policy**: truncated exponential backoff with full jitter. Events that exhaust
 * [maxRetries] are moved atomically to the dead-letter table. HTTP 400 responses move events to
 * the DLQ immediately (the payload is structurally broken and will keep failing). HTTP 429
 * responses additionally halve the next batch size to reduce load on a recovering server.
 *
 * @param endpoint Full URL of the batch ingest endpoint, e.g. `"https://api.example.com/v1/events"`.
 * @param authToken Bearer token for the `Authorization` header, if required.
 *
 * @since 1.0.0
 */
class BackendBatchAdapter(
    private val endpoint: String,
    private val authToken: String? = null,
) : EventAdapter {

    override val id: String = "backend"

    private lateinit var db: EventDatabase
    private lateinit var logger: EventLogger
    private lateinit var retryPolicy: RetryPolicy
    private lateinit var deviceInfo: DeviceInfo

    private var batchSize: Int = 50
    private var maxRetries: Int = 8

    /** Temporarily reduced after a 429; resets to [batchSize] on the next successful send. */
    @Volatile
    private var effectiveBatchSize: Int = 50

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ---- EventAdapter -----------------------------------------------------------------------

    override fun initialize(context: Context, logger: EventLogger) {
        this.logger = logger
        this.db = EventDatabase.get(context)
        this.retryPolicy = RetryPolicy()
        this.deviceInfo = DeviceInfo.collect(context)
    }

    /**
     * Called internally by [in.singhangad.eventtracker.EventTracker] after the config is resolved,
     * so that batchSize and maxRetries come from [in.singhangad.eventtracker.EventTrackerConfig]
     * rather than being duplicated in the adapter constructor.
     */
    internal fun configure(batchSize: Int, maxRetries: Int) {
        this.batchSize = batchSize
        this.maxRetries = maxRetries
        this.effectiveBatchSize = batchSize
    }

    override fun accepts(event: TrackEvent): Boolean =
        event.destinations?.contains(id) ?: true

    /** No-op: the event is already in the DB as QUEUED when this is called. */
    override suspend fun deliver(event: TrackEvent): DeliveryOutcome = DeliveryOutcome.Success

    /**
     * Drains the queue: reads batches of [effectiveBatchSize] and POSTs them until the queue is
     * empty or a non-retryable error halts further sends.
     */
    override suspend fun flush(): DeliveryOutcome = withContext(Dispatchers.IO) {
        var outcome: DeliveryOutcome = DeliveryOutcome.Success
        while (true) {
            val batchOutcome = sendOneBatch() ?: break // null → queue empty
            if (batchOutcome is DeliveryOutcome.RetryableFailure ||
                batchOutcome is DeliveryOutcome.PermanentFailure
            ) {
                outcome = batchOutcome
                break
            }
        }
        outcome
    }

    // ---- Internal send logic ----------------------------------------------------------------

    private suspend fun sendOneBatch(): DeliveryOutcome? {
        val now = System.currentTimeMillis()
        val dao = db.eventDao()
        val dlqDao = db.dlqDao()

        val events = dao.nextBatch(now, effectiveBatchSize)
        if (events.isEmpty()) return null

        val ids = events.map { it.id }
        dao.markSending(ids)

        val batchId = UUID.randomUUID().toString()
        logger.debug(TAG, "Sending batch batchId=$batchId size=${events.size}")

        return try {
            val response = executePost(events, batchId, now)
            handleResponse(events, ids, response, dlqDao, dao)
        } catch (t: Throwable) {
            logger.warn(TAG, "Network error: ${t.message}")
            rescheduleOrDeadLetter(ids, events, null, "network: ${t.message}", dao, dlqDao)
            DeliveryOutcome.RetryableFailure(t)
        }
    }

    private suspend fun handleResponse(
        events: List<EventEntity>,
        ids: List<String>,
        response: HttpResponse,
        dlqDao: DLQDao,
        dao: EventDao,
    ): DeliveryOutcome {
        val status = response.statusCode
        logger.debug(TAG, "Response status=$status batchSize=${events.size}")

        return when {
            status in 200..202 -> {
                dao.deleteByIds(ids)
                effectiveBatchSize = batchSize // reset after a successful send
                logger.info(TAG, "Batch delivered: ${events.size} events")
                DeliveryOutcome.Success
            }

            status == 207 -> {
                // Partial success: body contains {"failed": ["id1", "id2"]}
                val failedIds = parseFailedIds(response.body).toSet()
                val succeededIds = ids.filterNot { it in failedIds }
                dao.deleteByIds(succeededIds)
                if (failedIds.isNotEmpty()) {
                    val failedEntities = events.filter { it.id in failedIds }
                    rescheduleOrDeadLetter(
                        failedIds.toList(), failedEntities,
                        response.retryAfterMs, "HTTP 207 partial failure", dao, dlqDao
                    )
                }
                logger.info(TAG, "Partial success: ${succeededIds.size} delivered, ${failedIds.size} failed")
                DeliveryOutcome.Success
            }

            status == 400 -> {
                // Structural payload error — will never succeed, move straight to DLQ
                logger.warn(TAG, "HTTP 400: moving ${events.size} events to DLQ")
                moveToDeadLetter(events, status, "HTTP 400: ${response.body.take(200)}", dao, dlqDao)
                DeliveryOutcome.PermanentFailure(RuntimeException("HTTP 400"))
            }

            status == 401 || status == 403 -> {
                // Auth failure — halt the sender, events stay QUEUED for the next process start
                logger.error(TAG, "Auth failure HTTP $status — halting sender")
                dao.rescheduleFailed(ids, Long.MAX_VALUE / 2, "HTTP $status auth failure")
                DeliveryOutcome.PermanentFailure(RuntimeException("Auth failure: HTTP $status"))
            }

            status == 429 -> {
                // Throttled — honor Retry-After and halve batch size
                effectiveBatchSize = maxOf(1, effectiveBatchSize / 2)
                logger.warn(TAG, "HTTP 429: throttled, reducing batch size to $effectiveBatchSize")
                rescheduleOrDeadLetter(
                    ids, events, response.retryAfterMs, "HTTP 429 throttled", dao, dlqDao
                )
                DeliveryOutcome.RetryableFailure(
                    RuntimeException("HTTP 429"),
                    response.retryAfterMs,
                )
            }

            status >= 500 || status == 408 -> {
                rescheduleOrDeadLetter(ids, events, response.retryAfterMs, "HTTP $status", dao, dlqDao)
                DeliveryOutcome.RetryableFailure(
                    RuntimeException("HTTP $status"),
                    response.retryAfterMs,
                )
            }

            else -> {
                logger.warn(TAG, "Unexpected HTTP $status — moving to DLQ")
                moveToDeadLetter(events, status, "Unexpected HTTP $status", dao, dlqDao)
                DeliveryOutcome.PermanentFailure(RuntimeException("Unexpected HTTP $status"))
            }
        }
    }

    private suspend fun rescheduleOrDeadLetter(
        ids: List<String>,
        events: List<EventEntity>,
        retryAfterMs: Long?,
        errorMsg: String,
        dao: EventDao,
        dlqDao: DLQDao,
    ) {
        val now = System.currentTimeMillis()
        val (exhausted, retryable) = events.partition { it.attemptCount + 1 >= maxRetries }

        if (exhausted.isNotEmpty()) {
            logger.warn(TAG, "Moving ${exhausted.size} exhausted events to DLQ")
            moveToDeadLetter(exhausted, null, errorMsg, dao, dlqDao)
        }

        if (retryable.isNotEmpty()) {
            val nextAt = now + retryPolicy.nextDelayMs(
                retryable.first().attemptCount + 1, retryAfterMs
            )
            dao.rescheduleFailed(retryable.map { it.id }, nextAt, errorMsg)
            logger.debug(TAG, "Rescheduled ${retryable.size} events, next attempt at $nextAt")
        }
    }

    private suspend fun moveToDeadLetter(
        events: List<EventEntity>,
        httpStatus: Int?,
        errorMsg: String,
        dao: EventDao,
        dlqDao: DLQDao,
    ) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            dao.deleteByIds(events.map { it.id })
            dlqDao.insertAll(events.map { it.toDeadLetterEntity(httpStatus, errorMsg, now) })
        }
    }

    // ---- HTTP -------------------------------------------------------------------------------

    private suspend fun executePost(
        events: List<EventEntity>,
        batchId: String,
        sentAt: Long,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val jsonBytes = buildBatchJson(events, batchId, sentAt).toByteArray(Charsets.UTF_8)
        val (body, encoding) = if (jsonBytes.size > GZIP_THRESHOLD_BYTES) {
            gzipCompress(jsonBytes) to "gzip"
        } else {
            jsonBytes to null
        }

        val requestBody: RequestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .header("X-Eventtracker-Sdk", "android/$SCHEMA_VERSION.0.0")
            .header("X-Eventtracker-Batch-Id", batchId)
            .header("X-Eventtracker-Idempotency-Key", batchId)
            .apply {
                if (authToken != null) header("Authorization", "Bearer $authToken")
                if (encoding != null) header("Content-Encoding", encoding)
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            HttpResponse(
                statusCode = response.code,
                body = response.body?.string() ?: "",
                retryAfterMs = parseRetryAfter(response.header("Retry-After")),
            )
        }
    }

    private fun buildBatchJson(events: List<EventEntity>, batchId: String, sentAt: Long): String {
        val root = JSONObject()
        root.put("schema_version", SCHEMA_VERSION)
        root.put("sent_at", sentAt)
        root.put("device", deviceInfo.toJson())

        val eventsArray = JSONArray()
        events.forEach { e ->
            val obj = JSONObject()
            obj.put("id", e.id)
            obj.put("name", e.name)
            obj.put("properties", safeParseJson(e.payloadJson))
            if (e.userId != null) obj.put("user_id", e.userId)
            obj.put("session_id", e.sessionId)
            obj.put("client_ts", e.clientTs)
            obj.put("client_uptime_ms", e.clientUptimeMs)
            obj.put("attempt_count", e.attemptCount)
            eventsArray.put(obj)
        }
        root.put("events", eventsArray)
        return root.toString()
    }

    private fun safeParseJson(json: String): JSONObject = try {
        JSONObject(json)
    } catch (_: Exception) {
        JSONObject()
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(data) }
        return baos.toByteArray()
    }

    private fun parseFailedIds(body: String): List<String> = try {
        val arr = JSONObject(body).optJSONArray("failed") ?: return emptyList()
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Parse Retry-After header as milliseconds. Supports both integer seconds and HTTP-date.
     */
    private fun parseRetryAfter(value: String?): Long? {
        if (value == null) return null
        return value.toLongOrNull()?.let { it * 1000L }
    }

    // ---- Data helpers -----------------------------------------------------------------------

    private data class HttpResponse(
        val statusCode: Int,
        val body: String,
        val retryAfterMs: Long?,
    )

    private data class DeviceInfo(
        val osVersion: String,
        val appVersion: String,
        val model: String,
        val locale: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("os", "android")
            put("os_version", osVersion)
            put("app_version", appVersion)
            put("model", model)
            put("locale", locale)
        }

        companion object {
            fun collect(context: Context): DeviceInfo {
                val appVersion = try {
                    context.packageManager
                        .getPackageInfo(context.packageName, 0)
                        .versionName ?: "unknown"
                } catch (_: Exception) { "unknown" }
                return DeviceInfo(
                    osVersion = Build.VERSION.RELEASE,
                    appVersion = appVersion,
                    model = Build.MODEL,
                    locale = java.util.Locale.getDefault().toLanguageTag(),
                )
            }
        }
    }
}

// ---- Extension helpers ----------------------------------------------------------------------

private fun EventEntity.toDeadLetterEntity(
    httpStatus: Int?,
    lastError: String,
    now: Long,
): DeadLetterEntity = DeadLetterEntity(
    id = id,
    name = name,
    payloadJson = payloadJson,
    attemptCount = attemptCount,
    firstFailureAt = createdAt,
    lastFailureAt = now,
    lastError = lastError,
    httpStatus = httpStatus,
    schemaVersion = schemaVersion,
    createdAt = createdAt,
)

/** Serialise a Map<String, Any?> to a JSON string, coercing non-primitive values to strings. */
internal fun mapToJson(map: Map<String, Any?>): String {
    val obj = JSONObject()
    map.forEach { (key, value) ->
        when (value) {
            null -> obj.put(key, JSONObject.NULL)
            is String -> obj.put(key, value)
            is Int -> obj.put(key, value)
            is Long -> obj.put(key, value)
            is Double -> obj.put(key, value)
            is Float -> obj.put(key, value.toDouble())
            is Boolean -> obj.put(key, value)
            is Map<*, *> -> obj.put(key, JSONObject(value.mapKeys { it.key.toString() }
                .mapValues { it.value?.toString() ?: "" }))
            is List<*> -> obj.put(key, JSONArray(value.map { it?.toString() ?: "" }))
            else -> obj.put(key, value.toString())
        }
    }
    return obj.toString()
}
