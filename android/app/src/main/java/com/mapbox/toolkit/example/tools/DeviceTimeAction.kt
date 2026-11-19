package com.mapbox.toolkit.example.tools

import com.mapbox.via.kit.actions.ViaNoArgsAction
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Reads the device's local date, time and time zone. */
class DeviceTimeAction(private val clock: Clock = Clock.systemDefaultZone()) : ViaNoArgsAction() {
  override val id = "device_time"

  override val name = "Device time"

  override val description = "Read the current local date, time, and time zone from the user's device."

  override val examples = listOf("What time is it?", "What's the date today?")

  override val supportsConcurrentExecution = true

  override suspend fun execute(): Result<String> {
    val now = ZonedDateTime.now(clock)
    return Result.success("The device's local time is ${now.format(FORMATTER)}, time zone ${now.zone.id}.")
  }

  private companion object {
    val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a 'on' EEEE, MMMM d, yyyy", Locale.US)
  }
}
