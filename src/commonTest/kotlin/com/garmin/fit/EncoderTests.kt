/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

import com.garmin.fit.types.File
import com.garmin.fit.types.Manufacturer
import com.garmin.fit.types.Sport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EncoderTests {
    private fun fileId(): FileIdMesg = FileIdMesg().apply {
        type = File.ACTIVITY
        manufacturer = Manufacturer.GARMIN
        serialNumber = 1234u
    }

    @Test
    fun anEncodedFileIsAValidFitFile() {
        val bytes = encodeFit { write(fileId()) }

        assertTrue(FitDecoder.isFit(bytes))
        assertTrue(FitDecoder(bytes).checkIntegrity())
    }

    @Test
    fun theHeaderRecordsTheSizeOfWhatFollowsIt() {
        val bytes = encodeFit { write(fileId()) }
        val header = FileHeader.read(ByteReader(bytes))

        assertEquals(Fit.HEADER_WITH_CRC_SIZE, header.headerSize)
        assertEquals(bytes.size - Fit.HEADER_WITH_CRC_SIZE - Fit.CRC_SIZE, header.dataSize.toInt())
        assertEquals(Fit.PROFILE_VERSION, header.profileVersion)
    }

    @Test
    fun whatIsWrittenIsWhatDecodes() {
        val bytes = encodeFit { write(fileId()) }
        val result = FitDecoder(bytes).decode()

        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertEquals(1, result.messages.fileIdMesgs.size)

        val decoded = result.messages.fileIdMesgs[0]
        assertEquals(File.ACTIVITY, decoded.type)
        assertEquals(Manufacturer.GARMIN, decoded.manufacturer)
        assertEquals(1234u, decoded.serialNumber)
    }

    /** A run of same-shaped messages should cost one definition, not one each. */
    @Test
    fun aRepeatedLayoutIsDefinedOnlyOnce() {
        val once = encodeFit {
            write(fileId())
            write(RecordMesg().apply { heartRate = 100u })
        }
        val threeRecords = encodeFit {
            write(fileId())
            write(RecordMesg().apply { heartRate = 100u })
            write(RecordMesg().apply { heartRate = 101u })
            write(RecordMesg().apply { heartRate = 102u })
        }

        // Each extra record adds only its 1-byte header plus its 1-byte value.
        assertEquals(once.size + 4, threeRecords.size)
    }

    @Test
    fun aChangedLayoutEmitsAFreshDefinition() {
        val bytes = encodeFit {
            write(fileId())
            write(RecordMesg().apply { heartRate = 100u })
            write(RecordMesg().apply { heartRate = 101u; cadence = 90u })
        }

        val result = FitDecoder(bytes).decode()
        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertEquals(2, result.messages.recordMesgs.size)
        assertEquals(90u.toUByte(), result.messages.recordMesgs[1].cadence)
    }

    @Test
    fun everyBaseTypeSurvivesARoundTrip() {
        val session = SessionMesg().apply {
            sport = Sport.CYCLING
            totalElapsedTime = 3661.5
            totalDistance = 42195.0
            totalCalories = 500u
            avgHeartRate = 145u
            maxSpeed = 12.345
        }

        val decoded = FitDecoder(encodeFit { write(fileId()); write(session) }).decode()
            .messages.sessionMesgs.single()

        assertEquals(Sport.CYCLING, decoded.sport)
        assertEquals(3661.5, decoded.totalElapsedTime)
        assertEquals(42195.0, decoded.totalDistance)
        assertEquals(500u.toUShort(), decoded.totalCalories)
        assertEquals(145u.toUByte(), decoded.avgHeartRate)
        assertNotNull(decoded.maxSpeed)
    }

    @Test
    fun stringsSurviveARoundTrip() {
        val decoded = FitDecoder(
            encodeFit {
                write(FileIdMesg().apply { type = File.ACTIVITY; productName = "a test device" })
            },
        ).decode().messages.fileIdMesgs.single()

        assertEquals("a test device", decoded.productName)
    }

    @Test
    fun anArrayFieldSurvivesARoundTrip() {
        val decoded = FitDecoder(
            encodeFit {
                write(fileId())
                write(RecordMesg().apply { compressedSpeedDistance = listOf(1u, 2u, 3u) })
            },
        ).decode(DecodeOptions(expandComponents = false)).messages.recordMesgs.single()

        assertEquals(listOf<UByte?>(1u, 2u, 3u), decoded.compressedSpeedDistance)
    }

    @Test
    fun anAbsentFieldIsNotWrittenAtAll() {
        val bytes = encodeFit { write(RecordMesg()) }
        val decoded = FitDecoder(bytes).decode().messages.recordMesgs.single()
        assertEquals(0, decoded.fieldList.size)
    }

    @Test
    fun manyMessagesEncodeAndDecode() {
        val bytes = encodeFit {
            write(fileId())
            repeat(1000) { i -> write(RecordMesg().apply { heartRate = (60 + i % 100).toUByte() }) }
        }

        val result = FitDecoder(bytes).decode()
        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertEquals(1000, result.messages.recordMesgs.size)
        assertEquals(60u.toUByte(), result.messages.recordMesgs[0].heartRate)
        assertEquals(159u.toUByte(), result.messages.recordMesgs[99].heartRate)
    }

    @Test
    fun anEncoderCannotBeUsedAfterClosing() {
        val encoder = FitEncoder()
        encoder.write(fileId())
        encoder.close()

        assertFailsWith<IllegalStateException> { encoder.write(fileId()) }
        assertFailsWith<IllegalStateException> { encoder.close() }
    }

    @Test
    fun theMessageCountTracksWhatWasWritten() {
        val encoder = FitEncoder()
        assertEquals(0, encoder.mesgCount)
        encoder.write(fileId())
        encoder.write(listOf(RecordMesg(), RecordMesg()))
        assertEquals(3, encoder.mesgCount)
        encoder.close()
    }

    /** Two encoders given the same messages must produce the same bytes. */
    @Test
    fun encodingIsDeterministic() {
        fun build() = encodeFit {
            write(fileId())
            write(RecordMesg().apply { heartRate = 140u; cadence = 85u })
        }
        assertContentEquals(build(), build())
    }
}
