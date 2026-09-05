package com.operator.backend.usage

import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One provider call. Cost is only present when the provider reports it. */
@Serializable
data class UsageRecord(
    val at: String,
    val kind: String,
    val provider: String,
    val model: String,
    val tier: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val latencyMillis: Long = 0,
    val costUsd: Double? = null,
    val sessionId: String? = null,
    val failed: Boolean = false,
)

@Serializable
data class UsageTotals(
    val calls: Int = 0,
    val failures: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double? = null,
    val averageLatencyMillis: Long = 0,
)

@Serializable
data class UsageReport(
    val today: UsageTotals,
    val month: UsageTotals,
    val allTime: UsageTotals,
    val byModel: Map<String, UsageTotals>,
    val recent: List<UsageRecord>,
    val note: String,
)

/**
 * In-process usage accounting (brief: track transcription, tokens, TTS characters, provider,
 * model, timestamp, session; support daily/monthly/session totals). Milestone 6 records model
 * calls; transcription and TTS use the same [record] with a different `kind`.
 *
 * Deliberately in-memory and bounded for now: it is diagnostics, not billing, and it must not
 * become a second database write on the latency path. Persisting it is a later step, which is
 * why totals are computed from records rather than stored (ADR-024).
 */
class UsageTracker(
    private val capacity: Int = 500,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lock = ReentrantLock()
    private val records = ArrayDeque<UsageRecord>()
    private var allTime = UsageTotals()
    private var costSeen = false

    fun record(
        kind: String,
        provider: String,
        model: String,
        tier: String? = null,
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        latencyMillis: Long = 0,
        costUsd: Double? = null,
        sessionId: String? = null,
        failed: Boolean = false,
    ): UsageRecord {
        val record = UsageRecord(
            at = Instant.now(clock).toString(), kind = kind, provider = provider, model = model, tier = tier,
            inputTokens = inputTokens, outputTokens = outputTokens, latencyMillis = latencyMillis,
            costUsd = costUsd, sessionId = sessionId, failed = failed,
        )
        lock.withLock {
            records.addLast(record)
            while (records.size > capacity) records.removeFirst()
            if (costUsd != null) costSeen = true
            allTime = allTime.add(record, costSeen)
        }
        return record
    }

    fun report(recentLimit: Int = 20): UsageReport = lock.withLock {
        val now = Instant.now(clock).atZone(ZoneOffset.UTC)
        val startOfDay = now.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
        val startOfMonth = now.toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        UsageReport(
            today = totals(records.filter { Instant.parse(it.at) >= startOfDay }),
            month = totals(records.filter { Instant.parse(it.at) >= startOfMonth }),
            allTime = allTime,
            byModel = records.groupBy { it.model }.mapValues { (_, rs) -> totals(rs) },
            recent = records.toList().takeLast(recentLimit).reversed(),
            note = "In-process counters since startup; the last $capacity calls are retained. " +
                "Costs appear only for providers that report them. Not billing-grade.",
        )
    }

    private fun totals(rs: List<UsageRecord>): UsageTotals = rs.fold(UsageTotals()) { acc, r -> acc.add(r, costSeen) }

    private fun UsageTotals.add(r: UsageRecord, trackCost: Boolean): UsageTotals {
        val calls = this.calls + 1
        val totalLatency = averageLatencyMillis * this.calls + r.latencyMillis
        return UsageTotals(
            calls = calls,
            failures = failures + if (r.failed) 1 else 0,
            inputTokens = inputTokens + r.inputTokens,
            outputTokens = outputTokens + r.outputTokens,
            costUsd = if (!trackCost) null else (costUsd ?: 0.0) + (r.costUsd ?: 0.0),
            averageLatencyMillis = totalLatency / calls,
        )
    }
}
