/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitStreamTests {
    @Test
    fun bitsComeOutLeastSignificantFirstAcrossAByteArray() {
        val stream = BitStream(listOf<Any?>(0xAAu.toUByte(), 0xFFu.toUByte()), BaseType.UINT8)
        val expected = listOf(0, 1, 0, 1, 0, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1)

        for ((index, bit) in expected.withIndex()) {
            assertTrue(stream.hasBitsAvailable)
            assertEquals(expected.size - index, stream.bitsAvailable)
            assertEquals(bit.toLong(), stream.readBits(1))
        }
        assertFalse(stream.hasBitsAvailable)
    }

    @Test
    fun aMultiByteValueIsReadLittleEndian() {
        val stream = BitStream(0xAAFFu.toUShort(), BaseType.UINT16)
        val expected = listOf(1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 0, 1, 0, 1, 0, 1)

        for (bit in expected) {
            assertEquals(bit.toLong(), stream.readBits(1))
        }
    }

    @Test
    fun readsSplitAcrossNibblesAndValues() {
        assertEquals(0xABL, BitStream(0xABu.toUByte(), BaseType.UINT8).readBits(8))

        BitStream(0xABu.toUByte(), BaseType.UINT8).let {
            assertEquals(0xBL, it.readBits(4))
            assertEquals(0xAL, it.readBits(4))
        }

        // Two 8-bit values read as one 16-bit quantity.
        BitStream(listOf<Any?>(0xAAu.toUByte(), 0xCBu.toUByte()), BaseType.UINT8).let {
            assertEquals(0xCBAAL, it.readBits(16))
        }

        // Four 8-bit values read as one 32-bit quantity.
        BitStream(listOf<Any?>(0xAAu.toUByte(), 0xCBu.toUByte(), 0xDEu.toUByte(), 0xFFu.toUByte()), BaseType.UINT8).let {
            assertEquals(0xFFDECBAAL, it.readBits(32))
        }
    }

    @Test
    fun signedReadsSignExtendFromTheRequestedWidth() {
        // 12 bits of all ones is -1, not 4095, when the destination is signed.
        val stream = BitStream(0x0FFFu.toUShort(), BaseType.UINT16)
        assertEquals(-1L, stream.readBits(12, signed = true))

        val unsigned = BitStream(0x0FFFu.toUShort(), BaseType.UINT16)
        assertEquals(4095L, unsigned.readBits(12, signed = false))
    }

    @Test
    fun aFullWidth64BitValueSurvivesUnchanged() {
        val stream = BitStream(0xABCDEF0123456789uL, BaseType.UINT64)
        assertEquals(0xABCDEF0123456789uL.toLong(), stream.readBits(64))
    }

    @Test
    fun readingPastTheEndFails() {
        val stream = BitStream(0xAAu.toUByte(), BaseType.UINT8)
        stream.readBits(8)
        assertFailsWith<FitFormatException> { stream.readBits(1) }
    }

    @Test
    fun resetRewindsToTheStart() {
        val stream = BitStream(0xABu.toUByte(), BaseType.UINT8)
        assertEquals(0xBL, stream.readBits(4))
        stream.reset()
        assertEquals(8, stream.bitsAvailable)
        assertEquals(0xABL, stream.readBits(8))
    }
}
