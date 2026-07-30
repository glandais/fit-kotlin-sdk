/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

import com.garmin.fit.types.Event
import com.garmin.fit.types.EventType
import com.garmin.fit.types.File
import com.garmin.fit.types.Manufacturer
import com.garmin.fit.types.Sport
import com.garmin.fit.types.SubSport
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Builds a complete activity file the way the Cookbook recipe does, then reads
 * it back.
 *
 * An activity file has a required shape: `file_id`, then the data, then the
 * `lap` / `session` / `activity` summaries that close it out. This is the
 * reference for what encoding a real file looks like.
 */
class EncodeActivityTests {
    private val start = Instant.fromEpochSeconds(1_000_000_000L)
    private val recordCount = 60

    private fun encodeActivity(): ByteArray = encodeFit {
        write(
            FileIdMesg().apply {
                type = File.ACTIVITY
                manufacturer = Manufacturer.DEVELOPMENT
                product = 0u
                serialNumber = 1234u
                timeCreated = start
            },
        )

        write(
            EventMesg().apply {
                timestamp = start
                event = Event.TIMER
                eventType = EventType.START
            },
        )

        repeat(recordCount) { i ->
            write(
                RecordMesg().apply {
                    timestamp = Instant.fromEpochSeconds(start.epochSeconds + i)
                    distance = i * 10.0
                    speed = 10.0
                    heartRate = (120 + i % 20).toUByte()
                    cadence = (80 + i % 10).toUByte()
                },
            )
        }

        val end = Instant.fromEpochSeconds(start.epochSeconds + recordCount)

        write(
            EventMesg().apply {
                timestamp = end
                event = Event.TIMER
                eventType = EventType.STOP_ALL
            },
        )

        write(
            LapMesg().apply {
                timestamp = end
                startTime = start
                totalElapsedTime = recordCount.toDouble()
                totalTimerTime = recordCount.toDouble()
                totalDistance = recordCount * 10.0
                messageIndex = 0u
            },
        )

        write(
            SessionMesg().apply {
                timestamp = end
                startTime = start
                totalElapsedTime = recordCount.toDouble()
                totalTimerTime = recordCount.toDouble()
                totalDistance = recordCount * 10.0
                sport = Sport.CYCLING
                subSport = SubSport.ROAD
                firstLapIndex = 0u
                numLaps = 1u
                messageIndex = 0u
            },
        )

        write(
            ActivityMesg().apply {
                timestamp = end
                totalTimerTime = recordCount.toDouble()
                numSessions = 1u
                // local_date_time is the same encoding as date_time, offset by
                // the recorder's UTC offset. This file is recorded in UTC.
                localTimestamp = end
            },
        )
    }

    @Test
    fun anEncodedActivityDecodesBackToWhatWasWritten() {
        val result = FitDecoder(encodeActivity()).decode()

        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertEquals(1, result.messages.fileIdMesgs.size)
        assertEquals(2, result.messages.eventMesgs.size)
        assertEquals(recordCount, result.messages.recordMesgs.size)
        assertEquals(1, result.messages.lapMesgs.size)
        assertEquals(1, result.messages.sessionMesgs.size)
        assertEquals(1, result.messages.activityMesgs.size)

        val session = result.messages.sessionMesgs.single()
        assertEquals(Sport.CYCLING, session.sport)
        assertEquals(SubSport.ROAD, session.subSport)
        assertEquals(recordCount * 10.0, session.totalDistance)
        assertEquals(start, session.startTime)
    }

    @Test
    fun recordsKeepTheirValuesAndOrder() {
        val records = FitDecoder(encodeActivity()).decode().messages.recordMesgs

        assertEquals(start, records.first().timestamp)
        assertEquals(120u.toUByte(), records.first().heartRate)
        assertEquals(0.0, records.first().distance)
        assertEquals((recordCount - 1) * 10.0, records.last().distance)

        val timestamps = records.mapNotNull { it.timestamp }
        assertEquals(timestamps.sorted(), timestamps)
    }

    @Test
    fun theEncodedFileIsSelfConsistent() {
        val bytes = encodeActivity()
        assertTrue(FitDecoder.isFit(bytes))
        assertTrue(FitDecoder(bytes).checkIntegrity())
    }
}
