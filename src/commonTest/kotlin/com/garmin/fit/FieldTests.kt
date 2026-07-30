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
