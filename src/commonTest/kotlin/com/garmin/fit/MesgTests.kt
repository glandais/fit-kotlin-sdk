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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /**
     * `data`'s subfields are all keyed off `event`. With no `event` field in the
     * message at all, [SubFieldMap.canMesgSupport] cannot even read a value to
     * compare, so nothing can activate: the plain field wins rather than any
     * subfield's scale, offset or type.
     */
    @Test
    fun aSubfieldFallsBackToTheMainFieldWhenItsReferenceFieldIsAbsent() {
        val event = EventMesg()
        event.setFieldValue(EventMesg.DATA_FIELD_NUM, 4096)

        assertFalse(event.hasField(EventMesg.EVENT_FIELD_NUM))
        assertEquals(Mesg.MAIN_FIELD, event.getActiveSubFieldIndex(EventMesg.DATA_FIELD_NUM))
        // The field's own (unscaled uint32) interpretation, not any subfield's.
        assertEquals(4096u, event.getFieldValue(EventMesg.DATA_FIELD_NUM))
        assertNull(event.gearChangeData)
        assertNull(event.batteryLevel)
    }

    /**
     * `event` is present, but holds a value none of `data`'s subfields map
     * (`WORKOUT` activates none of them). The reference field resolves fine;
     * it just does not select anything, so the fallback is the same as when the
     * reference field is missing outright.
     */
    @Test
    fun aSubfieldFallsBackToTheMainFieldWhenTheReferenceValueSelectsNone() {
        val event = EventMesg()
        event.event = Event.WORKOUT
        event.setFieldValue(EventMesg.DATA_FIELD_NUM, 777)

        assertEquals(Mesg.MAIN_FIELD, event.getActiveSubFieldIndex(EventMesg.DATA_FIELD_NUM))
        assertEquals(777u, event.getFieldValue(EventMesg.DATA_FIELD_NUM))
        assertNull(event.gearChangeData)
        assertNull(event.coursePointIndex)
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
        assertEquals(original.key, copied.key)
        assertEquals(original.fieldName, copied.fieldName)
        assertEquals(original.developerDataIndex, copied.developerDataIndex)

        // Neither field hands out its own array, so writing into what either
        // getter returns reaches nothing — not the other field, and not itself.
        original.applicationId!![0] = 0x7F
        assertEquals(0x01.toByte(), copied.applicationId!![0])
        assertEquals(0x01.toByte(), original.applicationId!![0])
        assertContentEquals(original.applicationId, copied.applicationId)
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

    /**
     * The per-type collections are read-only [List]s over the decoder's own
     * storage rather than the `MutableList`s they used to be, so a caller cannot
     * add to a decode result and have it look like something the file said.
     *
     * What a test can check is the other half of that contract: they are views,
     * not snapshots, so holding one costs nothing and it stays in file order as
     * the decode goes on. The read-only half lives in the declared type, and
     * these locals only compile because that type is [List].
     */
    @Test
    fun theMessageListsAreReadOnlyViewsOfTheDecodedFile() {
        val messages = FitMessages()
        val records: List<RecordMesg> = messages.recordMesgs
        val unknown: List<Mesg> = messages.unknownMesgs
        val descriptions: List<DeveloperFieldDescription> = messages.developerFieldDescriptions

        assertTrue(records.isEmpty())
        assertTrue(descriptions.isEmpty())

        messages.add(RecordMesg().apply { heartRate = 140u })
        messages.add(Mesg("unknown", 0xFFF0u))
        messages.add(RecordMesg().apply { heartRate = 141u })

        assertEquals(listOf<UByte?>(140u, 141u), records.map { it.heartRate })
        assertEquals(1, unknown.size)
    }

    /**
     * A message definition is a promise about the exact byte layout of the
     * records written under it. Dropping a field it declares would not lose that
     * field, it would shift every byte after it — and every later record read
     * under the same local message number — with nothing in the file to say so.
     */
    @Test
    fun writingUnderADefinitionThatDeclaresAnAbsentFieldIsRefused() {
        val record = RecordMesg().apply { heartRate = 140u; cadence = 90u }
        val definition = MesgDefinition.of(record, 0u)

        // The definition still declares cadence; the message no longer has it.
        record.removeField(RecordMesg.CADENCE_FIELD_NUM)

        val failure = assertFailsWith<FitFieldException> { record.write(ByteWriter(), definition) }
        assertTrue(failure.message!!.contains("${RecordMesg.CADENCE_FIELD_NUM}"), failure.message!!)
    }

    /** The same promise covers developer fields, which sit at the end of the record. */
    @Test
    fun writingUnderADefinitionThatDeclaresAnAbsentDeveloperFieldIsRefused() {
        val record = populatedRecord()
        val definition = MesgDefinition.of(record, 0u)

        val stripped = RecordMesg().apply { heartRate = 140u; compressedSpeedDistance = listOf(1u, 2u, 3u) }

        assertFailsWith<FitFieldException> { stripped.write(ByteWriter(), definition) }
    }

    /** And a definition the message does satisfy writes exactly what it promised. */
    @Test
    fun writingUnderAMatchingDefinitionEmitsTheDeclaredNumberOfBytes() {
        val record = populatedRecord()
        val definition = MesgDefinition.of(record, 0u)

        val writer = ByteWriter()
        record.write(writer, definition)

        // One record header byte, then exactly the declared payload.
        assertEquals(1 + definition.messageSize, writer.size)
    }
}
