package rw.plasmaco.ops.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rw.plasmaco.ops.data.repository.NotAllowedException
import rw.plasmaco.ops.domain.analytics.AlertSeverity
import rw.plasmaco.ops.domain.common.Issue
import rw.plasmaco.ops.domain.common.Outcome
import rw.plasmaco.ops.domain.common.Severity
import rw.plasmaco.ops.domain.inventory.StockStatus
import rw.plasmaco.ops.domain.inventory.TxnStatus
import rw.plasmaco.ops.domain.inventory.StockCountStatus
import rw.plasmaco.ops.domain.metrics.MetricValue
import rw.plasmaco.ops.domain.production.ReportStatus
import rw.plasmaco.ops.domain.units.Formatting
import rw.plasmaco.ops.domain.units.UnitOfMeasure
import rw.plasmaco.ops.ui.components.Tone
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-off messages for the snackbar host in the app shell. ViewModels post here so a message survives navigation
 * (e.g. "Report submitted" shown after returning to the list).
 */
@Singleton
class UserMessages @Inject constructor() {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    fun show(text: String) {
        _messages.tryEmit(text)
    }
}

/**
 * Base for screen ViewModels: runs user actions with a busy flag and turns refusals and permission errors into
 * readable messages. Exceptions are reported by type only; record contents and credentials are never logged.
 */
abstract class PlasmacoViewModel(protected val messages: UserMessages) : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    protected fun action(block: suspend () -> Unit): Job = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotAllowedException) {
            messages.show(e.message ?: "You are not allowed to do this")
        } catch (e: IllegalArgumentException) {
            // Business preconditions (require(...)) carry a message written for the person using the app.
            messages.show(e.message ?: "Some values are not valid")
        } catch (e: Exception) {
            messages.show("Something went wrong (${e.javaClass.simpleName}). Nothing was saved.")
        } finally {
            _busy.value = false
        }
    }

    /** Shows the first refusal reason; returns the value when the outcome is Ok. */
    protected fun <T> Outcome<T>.handle(success: String? = null): T? = when (this) {
        is Outcome.Ok -> {
            success?.let(messages::show)
            value
        }
        is Outcome.Refused -> {
            messages.show(issues.firstOrNull()?.message ?: "Not saved")
            null
        }
    }
}

// ------------------------------------------------------------------------------------------------ formatting

object Fmt {
    private val locale = Locale.UK
    val date: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale)
    val dateShort: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", locale)
    val dateNumeric: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", locale)
    val time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
    val dateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale)
    val monthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", locale)

    fun date(d: LocalDate?): String = d?.format(date) ?: "—"
    fun short(d: LocalDate?): String = d?.format(dateShort) ?: "—"
    fun time(t: LocalTime?): String = t?.format(time) ?: "—"
    fun dateTime(t: LocalDateTime?): String = t?.format(dateTime) ?: "—"
    fun instant(i: Instant?, zone: ZoneId): String = i?.atZone(zone)?.format(dateTime) ?: "—"
    fun epoch(ms: Long?, zone: ZoneId): String = ms?.let { Instant.ofEpochMilli(it).atZone(zone).format(dateTime) } ?: "—"

    fun qty(value: BigDecimal?, unit: UnitOfMeasure?): String =
        if (value == null) "—" else if (unit == null) Formatting.number(value, 2) else Formatting.quantity(value, unit)

    fun number(value: BigDecimal?, decimals: Int = 1): String = value?.let { Formatting.number(it, decimals) } ?: "—"

    /** Plain editable text for a stored quantity ("12.5", never "12.500"). */
    fun input(value: BigDecimal?): String = value?.stripTrailingZeros()?.toPlainString().orEmpty()

    fun metric(v: MetricValue, decimals: Int = 1): Pair<String?, String?> = when (v) {
        is MetricValue.Value -> when (v.unit) {
            "%" -> Formatting.number(v.value, decimals) to "%"
            "items" -> Formatting.number(v.value, 0) to null
            "min" -> Formatting.number(v.value, 0) to "min"
            else -> Formatting.compact(v.value) to v.unit
        }
        is MetricValue.NoData -> null to null
    }

    fun minutes(total: Long?): String = when {
        total == null -> "open"
        total < 60 -> "$total min"
        else -> "${total / 60} h ${total % 60} min"
    }

    fun enumLabel(name: String): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

fun ReportStatus.tone(): Tone = when (this) {
    ReportStatus.DRAFT -> Tone.NEUTRAL
    ReportStatus.SUBMITTED -> Tone.INFO
    ReportStatus.RETURNED -> Tone.WARNING
    ReportStatus.APPROVED -> Tone.SUCCESS
    ReportStatus.REJECTED -> Tone.DANGER
    ReportStatus.REOPENED -> Tone.WARNING
}

fun StockStatus.tone(): Tone = when (this) {
    StockStatus.IN_STOCK -> Tone.SUCCESS
    StockStatus.REORDER_REQUIRED -> Tone.WARNING
    StockStatus.LOW_STOCK -> Tone.WARNING
    StockStatus.OUT_OF_STOCK -> Tone.DANGER
}

fun TxnStatus.tone(): Tone = when (this) {
    TxnStatus.POSTED -> Tone.SUCCESS
    TxnStatus.PENDING_APPROVAL -> Tone.INFO
    TxnStatus.REJECTED -> Tone.DANGER
    TxnStatus.VOID -> Tone.NEUTRAL
}

fun TxnStatus.label(): String = when (this) {
    TxnStatus.POSTED -> "Posted"
    TxnStatus.PENDING_APPROVAL -> "Awaiting approval"
    TxnStatus.REJECTED -> "Rejected"
    TxnStatus.VOID -> "Void"
}

fun StockCountStatus.tone(): Tone = when (this) {
    StockCountStatus.DRAFT -> Tone.NEUTRAL
    StockCountStatus.SUBMITTED -> Tone.INFO
    StockCountStatus.APPROVED -> Tone.SUCCESS
    StockCountStatus.REJECTED -> Tone.DANGER
}

fun AlertSeverity.tone(): Tone = when (this) {
    AlertSeverity.CRITICAL -> Tone.DANGER
    AlertSeverity.WARNING -> Tone.WARNING
    AlertSeverity.INFO -> Tone.INFO
}

fun Severity.tone(): Tone = when (this) {
    Severity.ERROR -> Tone.DANGER
    Severity.WARNING -> Tone.WARNING
    Severity.INFO -> Tone.INFO
}

/** Field-level message for an input, from validation issues whose path equals [field]. */
fun List<Issue>.messageFor(field: String): String? = firstOrNull { it.field == field }?.message
