/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

// Same root package as FitJs.kt: these tests exercise the exported JS surface, not the
// Kotlin API the rest of the test suite covers.

import com.garmin.fit.TestData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun isDate(value: Any?): Boolean = js("value instanceof Date").unsafeCast<Boolean>()

private fun dateMillis(value: Any?): Double = value.asDynamic().getTime().unsafeCast<Double>()

class FitJsTests {
    @Test
    fun isFitFileRecognisesAFitHeader() {
        assertTrue(isFitFile(TestData.fitFileShort))
        assertTrue(!isFitFile(TestData.fitFileShortInvalidHeader))
    }

    @Test
    fun checkFitIntegrityFollowsTheCrcs() {
        assertTrue(checkFitIntegrity(TestData.fitFileShort))
        assertTrue(!checkFitIntegrity(TestData.fitFileShortInvalidCrc))
    }

    @Test
    fun decodeGroupsMessagesTheWayTheJavaScriptSdkDoes() {
        val result = decodeFit(TestData.fitFileShort)

        assertEquals(0, result.errors.size)
        assertEquals(1, result.mesgs.size)

        val fileIds = result.messages.asDynamic().fileIdMesgs
        assertEquals(1, fileIds.length as Int)
        assertEquals("fileId", result.mesgs[0].name)
        assertEquals(0, result.mesgs[0].num)
        assertTrue(fileIds[0] === result.mesgs[0], "grouped and ordered views share one object")
    }

    @Test
    fun decodeAppliesProfileTypesByDefault() {
        val fields = decodeFit(TestData.fitFileShort).mesgs[0].fields.asDynamic()

        assertEquals("activity", fields.type)
        assertEquals("garmin", fields.manufacturer)
        assertEquals("abcdefghi", fields.productName)
        // time_created is a date_time: seconds since the FIT epoch, handed over as a Date.
        assertTrue(isDate(fields.timeCreated), "time_created should decode to a Date")
        assertEquals(1000000000.0 + 631065600.0, dateMillis(fields.timeCreated) / 1000.0)
    }

    @Test
    fun applyTypesFalseLeavesTheFileSValues() {
        val fields = decodeFit(TestData.fitFileShort, js("({ applyTypes: false })")).mesgs[0].fields.asDynamic()

        assertEquals(4.0, fields.type)
        assertEquals(1.0, fields.manufacturer)
        assertEquals(1000000000.0, fields.timeCreated)
    }

    @Test
    fun decodeReportsErrorsRatherThanThrowing() {
        val result = decodeFit(TestData.fitFileShortInvalidCrc)

        assertTrue(result.errors.isNotEmpty(), "a corrupt CRC should be reported")
        assertTrue(result.errors[0].message.isNotEmpty())
    }

    @Test
    fun encodeRoundTripsThroughDecode() {
        val bytes = encodeFit(
            arrayOf<Any>(
                js("({ name: 'fileId', fields: { type: 'activity', manufacturer: 'garmin', serialNumber: 1234 } })"),
                js("({ name: 'record', fields: { heartRate: 140, cadence: 90 } })"),
            ),
        )

        assertTrue(isFitFile(bytes))
        val result = decodeFit(bytes)
        assertEquals(0, result.errors.size)
        assertEquals(2, result.mesgs.size)

        val fileId = result.mesgs[0].fields.asDynamic()
        assertEquals("activity", fileId.type)
        assertEquals("garmin", fileId.manufacturer)
        assertEquals(1234.0, fileId.serialNumber)

        val record = result.mesgs[1].fields.asDynamic()
        assertEquals(140.0, record.heartRate)
        assertEquals(90.0, record.cadence)
    }

    @Test
    fun encodeRejectsANameTheProfileDoesNotKnow() {
        var threw = false
        try {
            encodeFit(arrayOf<Any>(js("({ name: 'noSuchMessage', fields: {} })")))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "an unknown message name should not encode silently")
    }

    @Test
    fun fieldInfoReportsTheProfileMetadata() {
        val heartRate = assertNotNull(fitFieldInfo("record", "heartRate"))
        assertEquals(3, heartRate.num)
        assertEquals("bpm", heartRate.units)
        assertEquals("uint8", heartRate.baseType)

        val sport = assertNotNull(fitFieldInfo("session", "sport"))
        assertEquals("sport", sport.type)

        assertNull(fitFieldInfo("record", "noSuchField"))
        assertNull(fitFieldInfo("noSuchMessage", "heartRate"))
    }

    @Test
    fun profileVersionMatchesTheGeneratedProfile() {
        assertEquals("21.205.0", fitProfileVersion())
    }

    @Test
    fun messageNamesAreTheOnesDecodeHandsBack() {
        val names = fitMessageNames()
        assertTrue(names.contains("record"))
        assertTrue(names.contains("fileId"))
    }
}
