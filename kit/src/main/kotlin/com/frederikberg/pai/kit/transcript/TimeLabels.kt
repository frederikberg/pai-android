package com.frederikberg.pai.kit.transcript

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Times as a person reads them, in the zone of the phone. The zone and the clock are parameters so tests do not depend on either. */
class TimeLabels(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Instant = Instant::now,
) {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private val weekday = DateTimeFormatter.ofPattern("EEE")
    private val monthDay = DateTimeFormatter.ofPattern("MMM d")
    private val monthDayYear = DateTimeFormatter.ofPattern("MMM d, yyyy")

    /** `HH:mm` of a server timestamp, or null when there is none or it does not parse. */
    fun clock(iso: String?): String? = parse(iso)?.format(clock)

    /** The label a session list shows: the time today, the weekday and time this week, the date after that. */
    fun session(iso: String?): String {
        val at = parse(iso) ?: return ""
        val current = now().atZone(zone)
        val days = java.time.Duration.between(at, current).toHours() / 24.0
        return when {
            days < 1 && at.toLocalDate() == current.toLocalDate() -> at.format(clock)
            days < 7 -> "${at.format(weekday)} ${at.format(clock)}"
            at.year != current.year -> at.format(monthDayYear)
            else -> at.format(monthDay)
        }
    }

    private fun parse(iso: String?): ZonedDateTime? {
        if (iso.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(iso).atZoneSameInstant(zone)
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(iso).atZone(zone)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
