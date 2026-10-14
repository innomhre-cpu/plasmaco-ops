package rw.plasmaco.ops.domain.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Stable identifier for every persisted record. UUIDs keep ids unique across offline devices. */
typealias Id = String

fun newId(): Id = UUID.randomUUID().toString()

/** Abstracts "now" so business rules are deterministic in tests. */
interface TimeSource {
    fun now(): Instant
    fun zone(): ZoneId
    fun today(): LocalDate = LocalDate.ofInstant(now(), zone())
}

class SystemTimeSource(private val zoneId: ZoneId = ZoneId.of("Africa/Kigali")) : TimeSource {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = zoneId
}

class FixedTimeSource(var instant: Instant, private val zoneId: ZoneId = ZoneId.of("Africa/Kigali")) : TimeSource {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId
}

enum class Severity { ERROR, WARNING, INFO }

/**
 * A single validation finding. [field] is a dotted path ("entries[2].goodQty") so the UI can attach the
 * message to the right input; [code] is stable for tests and translations.
 */
data class Issue(
    val field: String,
    val code: String,
    val message: String,
    val severity: Severity = Severity.ERROR,
)

fun List<Issue>.hasErrors(): Boolean = any { it.severity == Severity.ERROR }
fun List<Issue>.errors(): List<Issue> = filter { it.severity == Severity.ERROR }
fun List<Issue>.warnings(): List<Issue> = filter { it.severity == Severity.WARNING }

/** Result of a business operation that can be refused with reasons. */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T, val warnings: List<Issue> = emptyList()) : Outcome<T>
    data class Refused(val issues: List<Issue>) : Outcome<Nothing> {
        constructor(field: String, code: String, message: String) : this(listOf(Issue(field, code, message)))
    }
}

fun <T> Outcome<T>.valueOrNull(): T? = (this as? Outcome.Ok<T>)?.value
fun <T> Outcome<T>.valueOrThrow(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Refused -> throw IllegalStateException(issues.joinToString { "${it.code}: ${it.message}" })
}

/** Decimal helpers. Quantities are always BigDecimal; doubles never enter a stored figure. */
object Decimals {
    val ZERO: BigDecimal = BigDecimal.ZERO
    val HUNDRED: BigDecimal = BigDecimal(100)

    /** Internal precision for stored quantities (metres, kg). */
    const val QTY_SCALE = 3

    fun of(text: String): BigDecimal = BigDecimal(text.trim())
    fun of(value: Long): BigDecimal = BigDecimal.valueOf(value)
    fun of(value: Int): BigDecimal = BigDecimal.valueOf(value.toLong())

    /** Parses user input. Only '.' is a decimal separator; ',' and spaces are treated as digit grouping. */
    fun parseInput(text: String): BigDecimal? {
        val cleaned = text.trim().replace(",", "").replace(" ", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        if (!cleaned.matches(Regex("""[-+]?\d*\.?\d+"""))) return null
        return runCatching { BigDecimal(cleaned) }.getOrNull()
    }

    fun qty(value: BigDecimal): BigDecimal = value.setScale(QTY_SCALE, RoundingMode.HALF_UP)

    /** a / b as a percentage with [scale] decimals, or null when b is zero. */
    fun percent(a: BigDecimal, b: BigDecimal, scale: Int = 1): BigDecimal? =
        if (b.signum() == 0) null else a.multiply(HUNDRED).divide(b, scale, RoundingMode.HALF_UP)

    /** a / b as a ratio (0..1) with [scale] decimals, or null when b is zero. */
    fun ratio(a: BigDecimal, b: BigDecimal, scale: Int = 4): BigDecimal? =
        if (b.signum() == 0) null else a.divide(b, scale, RoundingMode.HALF_UP)

    fun sum(values: Iterable<BigDecimal>): BigDecimal = values.fold(ZERO) { acc, v -> acc + v }

    /** Equal within [tolerance] — used for reconciliation checks, never for storage. */
    fun close(a: BigDecimal, b: BigDecimal, tolerance: BigDecimal = BigDecimal("0.001")): Boolean =
        (a - b).abs() <= tolerance
}

fun Iterable<BigDecimal>.sumDecimal(): BigDecimal = Decimals.sum(this)

/** Inclusive date range used by every filter, report and export. */
data class DateRange(val start: LocalDate, val end: LocalDate) {
    init {
        require(!end.isBefore(start)) { "Date range end $end is before start $start" }
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
    fun days(): Sequence<LocalDate> = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
    val dayCount: Long get() = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1

    companion object {
        fun single(date: LocalDate) = DateRange(date, date)
        fun month(year: Int, month: Int): DateRange {
            val start = LocalDate.of(year, month, 1)
            return DateRange(start, start.plusMonths(1).minusDays(1))
        }
    }
}
