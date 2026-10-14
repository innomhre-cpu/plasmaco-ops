package rw.plasmaco.ops.data.local

import androidx.room.TypeConverter
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Exact decimals and ISO dates as text: sortable for dates, lossless for quantities. */
class Converters {
    @TypeConverter fun decimalToText(v: BigDecimal?): String? = v?.toPlainString()
    @TypeConverter fun textToDecimal(v: String?): BigDecimal? = v?.let(::BigDecimal)

    @TypeConverter fun dateToText(v: LocalDate?): String? = v?.toString()
    @TypeConverter fun textToDate(v: String?): LocalDate? = v?.let(LocalDate::parse)

    @TypeConverter fun timeToText(v: LocalTime?): String? = v?.toString()
    @TypeConverter fun textToTime(v: String?): LocalTime? = v?.let(LocalTime::parse)

    @TypeConverter fun dateTimeToText(v: LocalDateTime?): String? = v?.toString()
    @TypeConverter fun textToDateTime(v: String?): LocalDateTime? = v?.let(LocalDateTime::parse)
}
