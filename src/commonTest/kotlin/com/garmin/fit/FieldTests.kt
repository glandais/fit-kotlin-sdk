/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldTests {
    @Test
    fun aValueComesBackOutAsItWentIn() {
        val field = Field("heartRate", 3u, BaseType.UINT8)
        field.setValue(120)
        assertEquals(120u.toUByte(), field.getValue())
        assertEquals(1, field.numValues)
        assertTrue(field.hasValues)
    }

    /** Altitude is stored as `(metres + 500) * 5`, and must survive the trip. */
    @Test
    fun scaleAndOffsetAreAppliedOnWriteAndUndoneOnRead() {
        val field = Field("altitude", 2u, BaseType.UINT16, scale = 5.0, offset = 500.0)
        field.setValue(1000.0)

        assertEquals(7500u.toUShort(), field.getRawValue())
        assertEquals(1000.0, field.getValue())
    }

    @Test
    fun theInvalidSentinelReadsBackAsNull() {
        val field = Field("heartRate", 3u, BaseType.UINT8)
        field.addRawValue(UByte.MAX_VALUE)

        assertNull(field.getValue())
        // The raw value is still there for anyone who needs the distinction.
        assertEquals(UByte.MAX_VALUE, field.getRawValue())
    }

    @Test
    fun readingBeyondTheStoredValuesGivesNull() {
        val field = Field("heartRate", 3u, BaseType.UINT8)
        field.setValue(120)
        assertNull(field.getValue(1))
        assertNull(field.getRawValue(5))
    }

    @Test
    fun aFieldHoldsAnArrayOfValues() {
        val field = Field("speeds", 4u, BaseType.UINT16)
        field.setValue(10, 0)
        field.setValue(20, 1)
        field.setValue(30, 2)

        assertEquals(3, field.numValues)
        assertEquals(10u.toUShort(), field.getValue(0))
        assertEquals(30u.toUShort(), field.getValue(2))
        assertContentEquals(listOf<Any?>(10u.toUShort(), 20u.toUShort(), 30u.toUShort()), field.toList())
    }

    @Test
    fun settingAValuePastTheEndPadsWithNulls() {
        val field = Field("speeds", 4u, BaseType.UINT16)
        field.setValue(30, 2)
        assertEquals(3, field.numValues)
        assertNull(field.getValue(0))
        assertEquals(30u.toUShort(), field.getValue(2))
    }

    @Test
    fun encodedSizeFollowsTheBaseTypeAndValueCount() {
        val field = Field("speeds", 4u, BaseType.UINT16)
        assertEquals(0, field.size)
        field.setValue(10, 0)
        assertEquals(2, field.size)
        field.setValue(20, 1)
        assertEquals(4, field.size)
    }

    @Test
    fun aStringFieldIsSizedByItsUtf8BytesPlusTerminator() {
        val field = Field("name", 4u, BaseType.STRING)
        field.setValue("abc")
        assertEquals(4, field.size)
    }

    @Test
    fun aFieldCannotGrowPastTheProtocolLimit() {
        val field = Field("blob", 1u, BaseType.UINT8)
        assertFailsWith<FitFieldException> { field.setValue(1, Fit.MAX_FIELD_SIZE) }
    }

    @Test
    fun valuesRoundTripThroughTheWire() {
        val field = Field("value", 1u, BaseType.SINT32)
        field.setValue(-123456)

        val writer = ByteWriter()
        field.write(writer)

        val roundTripped = Field("value", 1u, BaseType.SINT32)
        roundTripped.read(ByteReader(writer.toByteArray()), writer.size)
        assertEquals(-123456, roundTripped.getValue())
    }

    @Test
    fun aFieldWhoseValuesAreAllInvalidIsDroppedOnRead() {
        val field = Field("heartRate", 3u, BaseType.UINT8)
        field.read(ByteReader(TestData.bytes(0xFF, 0xFF)), 2)
        assertFalse(field.hasValues)
    }

    @Test
    fun aPartlyValidFieldIsKeptWhole() {
        val field = Field("heartRate", 3u, BaseType.UINT8)
        field.read(ByteReader(TestData.bytes(0xFF, 0x50)), 2)
        assertEquals(2, field.numValues)
        assertNull(field.getValue(0))
        assertEquals(0x50u.toUByte(), field.getValue(1))
    }

    /** The producer's width disagrees with the profile: skip, do not misalign. */
    @Test
    fun aSizeThatIsNotAWholeNumberOfValuesIsSkipped() {
        val field = Field("value", 1u, BaseType.UINT32)
        val reader = ByteReader(TestData.bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06))
        field.read(reader, 6)
        assertFalse(field.hasValues)
        assertEquals(6, reader.position)
    }

    @Test
    fun aStringFieldCanCarrySeveralNulSeparatedStrings() {
        val field = Field("names", 4u, BaseType.STRING)
        field.read(ByteReader(TestData.bytes(0x61, 0x00, 0x62, 0x00)), 4)
        assertEquals(listOf<Any?>("a", "b"), field.toList())
    }

    @Test
    fun anInvalidValueIsWrittenAsItsSentinelBytes() {
        val field = Field("heartRate", 3u, BaseType.UINT8)
        field.addRawValue(null)
        val writer = ByteWriter()
        field.write(writer)
        assertContentEquals(TestData.bytes(0xFF), writer.toByteArray())
    }

    /** Absent floats must go back out as 0xFFFFFFFF, not as a canonical NaN. */
    @Test
    fun anAbsentFloatKeepsItsAllOnesBitPattern() {
        val field = Field("value", 1u, BaseType.FLOAT32)
        field.read(ByteReader(TestData.bytes(0xFF, 0xFF, 0xFF, 0xFF)), 4)
        assertFalse(field.hasValues)

        val explicit = Field("value", 1u, BaseType.FLOAT32)
        explicit.addRawValue(Float.fromBits(-1))
        val writer = ByteWriter()
        explicit.write(writer)
        assertContentEquals(TestData.bytes(0xFF, 0xFF, 0xFF, 0xFF), writer.toByteArray())
    }

    /**
     * Component expansion divides by a scale, so a value that should land
     * exactly on 4087 arrives as 4086.99999... Truncating there loses a unit,
     * which showed up as speeds one millimetre per second low against the other
     * SDKs.
     */
    @Test
    fun integralConversionRoundsRatherThanTruncates() {
        assertEquals(4087u, BaseType.UINT32.correctRangeAndType(4086.9999999))
        assertEquals(4087u.toUShort(), BaseType.UINT16.correctRangeAndType(4086.9999999))
        assertEquals(5u.toUByte(), BaseType.UINT8.correctRangeAndType(4.6))
        assertEquals((-5).toByte(), BaseType.SINT8.correctRangeAndType(-4.6))
        // Floating point types keep their fractional part.
        assertEquals(4086.9999999, BaseType.FLOAT64.correctRangeAndType(4086.9999999))
    }

    /** Ties round to even, which is what the Python and JavaScript SDKs do. */
    @Test
    fun tiesRoundToEven() {
        assertEquals(4u.toUByte(), BaseType.UINT8.correctRangeAndType(4.5))
        assertEquals(6u.toUByte(), BaseType.UINT8.correctRangeAndType(5.5))
        assertEquals(2u.toUByte(), BaseType.UINT8.correctRangeAndType(2.5))
    }

    /**
     * `byte` is FIT's raw-bytes type: 0xFF is a legitimate element and only an
     * all-0xFF field means "absent". Nulling elements one by one would punch
     * holes in a UUID.
     */
    @Test
    fun aByteArrayTreatsFfAsDataUnlessEveryByteIsFf() {
        val uuid = Field("applicationId", 1u, BaseType.BYTE)
        uuid.read(ByteReader(TestData.bytes(0x01, 0xFF, 0x02, 0xFF)), 4)

        assertEquals(4, uuid.numValues)
        assertEquals(listOf<Any?>(1u.toUByte(), 0xFFu.toUByte(), 2u.toUByte(), 0xFFu.toUByte()), uuid.toList())

        val absent = Field("applicationId", 1u, BaseType.BYTE)
        absent.read(ByteReader(TestData.bytes(0xFF, 0xFF)), 2)
        assertFalse(absent.hasValues)
    }

    /**
     * A string field of nothing but NULs is the string type's invalid sentinel,
     * and has to be dropped exactly as an all-0xFF numeric field is. Keeping it
     * would make `hasField` true for a name the producer never wrote, which is
     * neither what the Python nor the JavaScript SDK reports.
     */
    @Test
    fun aStringFieldOfNothingButNulsIsDroppedOnRead() {
        val field = Field("productName", 8u, BaseType.STRING)
        field.read(ByteReader(TestData.bytes(0x00, 0x00, 0x00, 0x00)), 4)
        assertFalse(field.hasValues)
        assertEquals(0, field.numValues)
    }

    /** One empty string among several real ones is a hole, not an absent field. */
    @Test
    fun aStringFieldWithOneEmptyEntryIsKeptWhole() {
        val field = Field("names", 4u, BaseType.STRING)
        field.read(ByteReader(TestData.bytes(0x00, 0x61, 0x00, 0x00)), 4)

        assertEquals(2, field.numValues)
        assertNull(field.getValue(0))
        assertEquals("a", field.getValue(1))
    }

    /**
     * A subfield can be narrower than the field carrying it: `event.data` is a
     * uint32 whose `CoursePointIndex` subfield is a uint16. Storage stays the
     * field's own width — that is what the field definition declares and what
     * gets written — but a value the subfield cannot express is stored as the
     * invalid sentinel rather than as a number that reads back as null.
     */
    @Test
    fun aValueTooLargeForTheActiveSubfieldIsStoredAsInvalid() {
        val data = Factory.createField(Profile.MesgNum.EVENT, EventMesg.DATA_FIELD_NUM)
        assertNotNull(data)
        assertEquals(BaseType.UINT32, data.baseType)

        data.setValue(70000, 0, SubFieldSelector.Named("CoursePointIndex"))

        assertEquals(4, data.size, "the wire width follows the field, not the subfield")
        assertEquals(UInt.MAX_VALUE, data.getRawValue())
        assertNull(data.getValue(0, SubFieldSelector.Named("CoursePointIndex")))
    }

    /** In range, the value goes in and comes back out through the subfield unchanged. */
    @Test
    fun aValueWithinTheActiveSubfieldRangeRoundTrips() {
        val data = Factory.createField(Profile.MesgNum.EVENT, EventMesg.DATA_FIELD_NUM)
        assertNotNull(data)

        data.setValue(1234, 0, SubFieldSelector.Named("CoursePointIndex"))

        assertEquals(4, data.size)
        assertEquals(1234u, data.getRawValue())
        assertEquals(1234u, data.getValue(0, SubFieldSelector.Named("CoursePointIndex")))
    }

    /** Without a subfield the field's own range is the only one that applies. */
    @Test
    fun theFieldsOwnRangeStillAppliesWhenNoSubfieldIsSelected() {
        val data = Factory.createField(Profile.MesgNum.EVENT, EventMesg.DATA_FIELD_NUM)
        assertNotNull(data)

        data.setValue(70000)
        assertEquals(70000u, data.getRawValue())
        assertEquals(70000u, data.getValue())
    }

    /**
     * Field 0 of a message and developer field 0 of the same message are
     * different things, whatever they hold. Equality that cannot tell them apart
     * would let one stand in for the other in any set or list lookup.
     */
    @Test
    fun aProfileFieldNeverEqualsADeveloperFieldOfTheSameNumber() {
        val profileField = Field("doughnuts_earned", 0u, BaseType.UINT8).also { it.setValue(80u) }
        val developerField = DeveloperField(
            fieldName = "doughnuts_earned",
            fieldNum = 0u,
            baseType = BaseType.UINT8,
            developerDataIndex = 0u,
        ).also { it.setValue(80u) }

        assertNotEquals<FieldBase>(profileField, developerField)
        assertNotEquals<FieldBase>(developerField, profileField)
        assertFalse(listOf<FieldBase>(profileField).contains(developerField))
    }

    /** Two applications can declare the same field number; only the UUID separates them. */
    @Test
    fun twoDeveloperFieldsFromDifferentApplicationsAreNotEqual() {
        fun field(uuidByte: Byte) = DeveloperField(
            fieldName = "doughnuts_earned",
            fieldNum = 0u,
            baseType = BaseType.UINT8,
            developerDataIndex = 0u,
            applicationId = ByteArray(16) { uuidByte },
        ).also { it.setValue(80u) }

        assertNotEquals(field(1), field(2))
        assertEquals(field(1), field(1))
    }

    /**
     * The application UUID is this field's identity, so handing out the array
     * itself would let any caller rewrite which application the field belongs to.
     */
    @Test
    fun theApplicationIdIsCopiedOnTheWayInAndOnTheWayOut() {
        val uuid = ByteArray(16) { 1 }
        val field = DeveloperField(
            fieldName = "doughnuts_earned",
            fieldNum = 0u,
            baseType = BaseType.UINT8,
            developerDataIndex = 0u,
            applicationId = uuid,
        )

        // Rewriting the array that was passed in changes nothing.
        uuid[0] = 0x7F
        assertEquals(0x01.toByte(), field.applicationId!![0])

        // Nor does rewriting what the getter handed back.
        field.applicationId!![0] = 0x7F
        assertEquals(0x01.toByte(), field.applicationId!![0])
        assertEquals(List(16) { 1.toByte() }, field.key.applicationId)
    }

    @Test
    fun copyingAFieldCarriesItsMetadataAndValues() {
        val field = Field("altitude", 2u, BaseType.UINT16, scale = 5.0, offset = 500.0, units = "m")
        field.addComponent(FieldComponent(78u, false, 16, 5.0, 500.0))
        field.setValue(1000.0)

        val copy = Field(field)
        assertEquals(field.fieldName, copy.fieldName)
        assertEquals(field.scale, copy.scale)
        assertEquals(field.offset, copy.offset)
        assertEquals(field.units, copy.units)
        assertEquals(1, copy.components.size)
        assertEquals(1000.0, copy.getValue())
    }
}
