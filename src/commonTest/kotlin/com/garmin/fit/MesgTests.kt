/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

import com.garmin.fit.types.Event
import com.garmin.fit.types.Sport
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MesgTests {
    @Test
    fun typedAccessorsReadAndWriteTheSameFieldsAsTheUntypedApi() {
        val record = RecordMesg()
        record.heartRate = 140u

        assertEquals(140u.toUByte(), record.heartRate)
        // Same value, reached the other way.
        assertEquals(140u.toUByte(), record.getFieldValue(RecordMesg.HEART_RATE_FIELD_NUM))
        assertTrue(record.hasField(RecordMesg.HEART_RATE_FIELD_NUM))
    }

    @Test
    fun anUnsetFieldReadsAsNull() {
        val record = RecordMesg()
        assertNull(record.heartRate)
        assertNull(record.getField(RecordMesg.HEART_RATE_FIELD_NUM))
        assertFalse(record.hasField(RecordMesg.HEART_RATE_FIELD_NUM))
    }

    @Test
    fun aScaledFieldSurfacesInItsRealUnits() {
        val record = RecordMesg()
        record.altitude = 1234.0

        assertEquals(1234.0, record.altitude)
        // Stored as (metres + 500) * 5.
        assertEquals(8670u.toUShort(), record.getField(RecordMesg.ALTITUDE_FIELD_NUM)?.getRawValue())
    }

    @Test
    fun enumFieldsRoundTripThroughTheirProfileType() {
        val session = SessionMesg()
        session.sport = Sport.CYCLING

        assertEquals(Sport.CYCLING, session.sport)
        assertEquals(Sport.CYCLING.value, session.getFieldValue(SessionMesg.SPORT_FIELD_NUM))
    }

    /** An enum value the profile does not list must not be silently dropped. */
    @Test
    fun anUnknownEnumValueReadsAsInvalid() {
        val session = SessionMesg()
        session.setFieldValue(SessionMesg.SPORT_FIELD_NUM, 250)
        assertEquals(Sport.INVALID, session.sport)
    }

    @Test
    fun timestampsCrossTheFitEpoch() {
        val record = RecordMesg()
        val instant = Instant.fromEpochSeconds(1_000_000_000L)
        record.timestamp = instant

        assertEquals(instant, record.timestamp)
        assertEquals(
            (1_000_000_000L - Fit.EPOCH_OFFSET_SECONDS).toUInt(),
            record.getFieldValue(RecordMesg.TIMESTAMP_FIELD_NUM),
        )
    }

    @Test
    fun arrayFieldsAreIndexedAndCounted() {
        val record = RecordMesg()
        record.setCompressedSpeedDistance(0, 1u)
        record.setCompressedSpeedDistance(1, 2u)
        record.setCompressedSpeedDistance(2, 3u)

        assertEquals(3, record.numCompressedSpeedDistance)
        assertEquals(2u.toUByte(), record.getCompressedSpeedDistance(1))
        assertEquals(listOf<UByte?>(1u, 2u, 3u), record.compressedSpeedDistance)
    }

    @Test
    fun assigningAnArrayFieldReplacesEveryValue() {
        val record = RecordMesg()
        record.compressedSpeedDistance = listOf(1u, 2u, 3u)
        record.compressedSpeedDistance = listOf(9u)

        assertEquals(1, record.numCompressedSpeedDistance)
        assertEquals(9u.toUByte(), record.getCompressedSpeedDistance(0))
    }

    /** `event_data` means `gear_change_data` only when `event` says so. */
    @Test
    fun aSubfieldReadsOnlyWhenItsReferenceFieldSelectsIt() {
        val event = EventMesg()
        event.setFieldValue(EventMesg.DATA_FIELD_NUM, 0x00010203)

        // With no event set, the subfield does not apply.
        assertNull(event.gearChangeData)

        event.event = Event.REAR_GEAR_CHANGE
        assertNotNull(event.gearChangeData)
        assertEquals(0, event.getActiveSubFieldIndex(EventMesg.DATA_FIELD_NUM).let { if (it >= 0) 0 else -1 })
    }

    @Test
    fun copyingAMessageCarriesItsPopulatedFieldsOnly() {
        val record = RecordMesg()
        record.heartRate = 140u
        record.setField(Field("empty", 200u, BaseType.UINT8))

        val copy = RecordMesg(record)
        assertEquals(140u.toUByte(), copy.heartRate)
        assertEquals(1, copy.fieldList.size)
    }

    /** A copy owns its fields outright: writing to it must not reach the original. */
    @Test
    fun mutatingACopyLeavesTheOriginalIntact() {
        val record = populatedRecord()
        val copy = RecordMesg(record)

        assertNotSame(
            record.getField(RecordMesg.HEART_RATE_FIELD_NUM),
            copy.getField(RecordMesg.HEART_RATE_FIELD_NUM),
        )

        copy.heartRate = 60u
        copy.setCompressedSpeedDistance(3, 4u)
        val copiedDeveloperField = copy.developerFieldList.single()
        copiedDeveloperField.setValue(99u)

        assertEquals(140u.toUByte(), record.heartRate)
        assertEquals(3, record.numCompressedSpeedDistance)
        assertEquals(listOf<UByte?>(1u, 2u, 3u), record.compressedSpeedDistance)
        assertEquals(80u.toUByte(), record.developerFieldList.single().getValue())
    }

    /** And symmetrically: the original goes on being written to after the copy is taken. */
    @Test
    fun mutatingTheOriginalLeavesAnEarlierCopyIntact() {
        val record = populatedRecord()
        val copy = RecordMesg(record)

        record.heartRate = 60u
        record.setCompressedSpeedDistance(3, 4u)
        record.developerFieldList.single().setValue(99u)
        record.setFieldValue(RecordMesg.CADENCE_FIELD_NUM, 90u)

        assertEquals(140u.toUByte(), copy.heartRate)
        assertEquals(3, copy.numCompressedSpeedDistance)
        assertEquals(listOf<UByte?>(1u, 2u, 3u), copy.compressedSpeedDistance)
        assertEquals(80u.toUByte(), copy.developerFieldList.single().getValue())
        // A field added to the original after the copy was taken is not in it.
        assertFalse(copy.hasField(RecordMesg.CADENCE_FIELD_NUM))
    }

    /** The application UUID is a mutable array, so the copy needs its own. */
    @Test
    fun aCopiedDeveloperFieldDoesNotShareItsApplicationId() {
        val record = populatedRecord()
        val copy = RecordMesg(record)

        val original = record.developerFieldList.single()
        val copied = copy.developerFieldList.single()

        assertNotSame(original, copied)
        assertNotSame(original.applicationId, copied.applicationId)
        assertEquals(original.key, copied.key)
        assertEquals(original.fieldName, copied.fieldName)
        assertEquals(original.developerDataIndex, copied.developerDataIndex)

        original.applicationId!![0] = 0x7F
        assertEquals(0x01.toByte(), copied.applicationId!![0])
    }

    /** A record carrying a scalar field, a multi-value field and a developer field. */
    private fun populatedRecord(): RecordMesg {
        val record = RecordMesg()
        record.heartRate = 140u
        record.compressedSpeedDistance = listOf(1u, 2u, 3u)
        record.setDeveloperField(
            DeveloperField(
                fieldName = "doughnuts_earned",
                fieldNum = 0u,
                baseType = BaseType.UINT8,
                developerDataIndex = 0u,
                units = "doughnuts",
                applicationId = ByteArray(16) { 1 },
            ).also { it.setValue(80u) },
        )
        return record
    }

    @Test
    fun aMessageKnowsItsOwnIdentity() {
        val record = RecordMesg()
        assertEquals("Record", record.mesgName)
        assertEquals(Profile.MesgNum.RECORD, record.globalMesgNum)
    }

    @Test
    fun fitMessagesSortsByType() {
        val messages = FitMessages()
        messages.add(RecordMesg())
        messages.add(RecordMesg())
        messages.add(SessionMesg())
        messages.add(Mesg("unknown", 0xFFF0u))

        assertEquals(2, messages.recordMesgs.size)
        assertEquals(1, messages.sessionMesgs.size)
        assertEquals(1, messages.unknownMesgs.size)
        assertEquals(4, messages.size)
    }
}
