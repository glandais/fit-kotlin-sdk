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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaseTypeTests {
    @Test
    fun everyBaseTypeIsReachableFromItsWireId() {
        for (type in BaseType.entries) {
            assertEquals(type, BaseType.fromId(type.id))
        }
        assertNull(BaseType.fromId(0x7Fu))
    }

    @Test
    fun invalidSentinelsRoundTripThroughIsValid() {
        for (type in BaseType.entries) {
            if (type == BaseType.STRING) continue
            assertFalse(type.isValid(type.invalidValue()), "$type sentinel should read as invalid")
        }
    }

    @Test
    fun invalidBytesAreAsWideAsTheType() {
        for (type in BaseType.entries) {
            if (type == BaseType.STRING) continue
            assertEquals(type.size, type.invalidBytes().size, "$type invalid byte width")
        }
    }

    /**
     * FIT's float invalid is the all-ones bit pattern. It is a quiet NaN, but it
     * is not the one [Float.NaN] denotes, and writing the wrong one back out
     * would change the file.
     */
    @Test
    fun theFloatInvalidIsAllOnesNotTheCanonicalNaN() {
        val invalid32 = BaseType.FLOAT32.invalidValue() as Float
        assertTrue(invalid32.isNaN())
        assertEquals(-1, invalid32.toRawBits())
        assertContentEquals(byteArrayOf(-1, -1, -1, -1), BaseType.FLOAT32.invalidBytes())

        val invalid64 = BaseType.FLOAT64.invalidValue() as Double
        assertTrue(invalid64.isNaN())
        assertEquals(-1L, invalid64.toRawBits())

        // A canonical NaN still reads as invalid, so files written by other
        // producers decode correctly.
        assertFalse(BaseType.FLOAT32.isValid(Float.NaN))
        assertFalse(BaseType.FLOAT64.isValid(Double.NaN))
    }

    @Test
    fun theZTypesReserveZeroRatherThanTheirMaximum() {
        assertEquals(UByte.MIN_VALUE, BaseType.UINT8Z.invalidValue())
        assertEquals(UShort.MIN_VALUE, BaseType.UINT16Z.invalidValue())
        assertEquals(UInt.MIN_VALUE, BaseType.UINT32Z.invalidValue())
        assertEquals(ULong.MIN_VALUE, BaseType.UINT64Z.invalidValue())

        assertTrue(BaseType.UINT8Z.isValid(255u.toUByte()))
        assertFalse(BaseType.UINT8Z.isValid(0u.toUByte()))
    }

    @Test
    fun correctRangeAndTypeBoxesValuesAsTheTypesKotlinType() {
        assertEquals(7u.toUByte(), BaseType.UINT8.correctRangeAndType(7))
        assertEquals(7.toShort(), BaseType.SINT16.correctRangeAndType(7))
        assertEquals(7u.toUShort(), BaseType.UINT16.correctRangeAndType(7))
        assertEquals(7, BaseType.SINT32.correctRangeAndType(7))
        assertEquals(7u, BaseType.UINT32.correctRangeAndType(7))
        assertEquals(7L, BaseType.SINT64.correctRangeAndType(7))
        assertEquals(7uL, BaseType.UINT64.correctRangeAndType(7))
        assertEquals(7.5f, BaseType.FLOAT32.correctRangeAndType(7.5))
        assertEquals(7.5, BaseType.FLOAT64.correctRangeAndType(7.5))
    }

    @Test
    fun outOfRangeValuesBecomeTheInvalidSentinel() {
        assertEquals(UByte.MAX_VALUE, BaseType.UINT8.correctRangeAndType(300))
        assertEquals(UByte.MAX_VALUE, BaseType.UINT8.correctRangeAndType(-1))
        assertEquals(Short.MAX_VALUE, BaseType.SINT16.correctRangeAndType(70000))
    }

    /**
     * A UINT64 has more significant bits than a Double's mantissa, so any
     * conversion routed through Double silently rounds.
     */
    @Test
    fun sixtyFourBitValuesKeepEveryBit() {
        val value = 0xABCDEF0123456789uL
        assertEquals(value, BaseType.UINT64.correctRangeAndType(value))
        assertEquals(Long.MIN_VALUE + 1, BaseType.SINT64.correctRangeAndType(Long.MIN_VALUE + 1))
    }

    @Test
    fun baseTypesAreClassifiedForScalingAndSignExtension() {
        assertFalse(BaseType.ENUM.isNumeric)
        assertFalse(BaseType.STRING.isNumeric)
        assertTrue(BaseType.UINT8.isNumeric)

        assertTrue(BaseType.FLOAT32.isFloatingPoint)
        assertFalse(BaseType.UINT32.isFloatingPoint)

        assertTrue(BaseType.SINT16.isSignedInteger)
        assertFalse(BaseType.UINT16.isSignedInteger)
        assertFalse(BaseType.FLOAT64.isSignedInteger)
    }

    @Test
    fun aBoxedValueMapsBackToTheBaseTypeThatHoldsIt() {
        assertEquals(BaseType.UINT8, BaseType.of(1u.toUByte()))
        assertEquals(BaseType.SINT8, BaseType.of(1.toByte()))
        assertEquals(BaseType.UINT16, BaseType.of(1u.toUShort()))
        assertEquals(BaseType.SINT32, BaseType.of(1))
        assertEquals(BaseType.UINT32, BaseType.of(1u))
        assertEquals(BaseType.SINT64, BaseType.of(1L))
        assertEquals(BaseType.UINT64, BaseType.of(1uL))
        assertEquals(BaseType.FLOAT32, BaseType.of(1.0f))
        assertEquals(BaseType.FLOAT64, BaseType.of(1.0))
        assertEquals(BaseType.STRING, BaseType.of("x"))
        assertNull(BaseType.of(Unit))
    }
}
