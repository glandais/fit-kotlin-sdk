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
import kotlin.test.assertTrue

class ByteReaderTests {
    @Test
    fun multiByteValuesHonourTheRequestedEndianness() {
        val bytes = TestData.bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)

        assertEquals(0x0201u.toUShort(), ByteReader(bytes).readUShort(Endianness.LITTLE))
        assertEquals(0x0102u.toUShort(), ByteReader(bytes).readUShort(Endianness.BIG))
        assertEquals(0x04030201u, ByteReader(bytes).readUInt(Endianness.LITTLE))
        assertEquals(0x01020304u, ByteReader(bytes).readUInt(Endianness.BIG))
        assertEquals(0x0807060504030201uL, ByteReader(bytes).readULong(Endianness.LITTLE))
        assertEquals(0x0102030405060708uL, ByteReader(bytes).readULong(Endianness.BIG))
    }

    @Test
    fun signedReadsPreserveTheSignBit() {
        assertEquals(-1, ByteReader(TestData.bytes(0xFF)).readByteSigned().toInt())
        assertEquals(-1, ByteReader(TestData.bytes(0xFF, 0xFF)).readShort().toInt())
        assertEquals(-1, ByteReader(TestData.bytes(0xFF, 0xFF, 0xFF, 0xFF)).readInt())
    }

    @Test
    fun floatsAreReadFromTheirBitPattern() {
        val writer = ByteWriter()
        writer.writeFloat(3.5f)
        writer.writeDouble(-1.25)
        val reader = ByteReader(writer.toByteArray())
        assertEquals(3.5f, reader.readFloat())
        assertEquals(-1.25, reader.readDouble())
    }

    @Test
    fun stringsStopAtTheirNulTerminator() {
        val reader = ByteReader(TestData.bytes(0x61, 0x62, 0x63, 0x00))
        assertEquals("abc", reader.readString(4))
        assertEquals(4, reader.position)
    }

    @Test
    fun positionTracksEveryRead() {
        val reader = ByteReader(TestData.fitFileShort)
        assertEquals(0, reader.position)
        assertEquals(TestData.fitFileShort.size, reader.bytesAvailable)

        reader.readByte()
        assertEquals(1, reader.position)

        reader.skip(3)
        assertEquals(4, reader.position)

        reader.seek(0)
        assertEquals(0, reader.position)

        reader.reset()
        assertEquals(0, reader.position)
    }

    @Test
    fun peekDoesNotConsume() {
        val reader = ByteReader(TestData.fitFileShort)
        assertEquals(0x0Eu.toUByte(), reader.peekByte())
        assertEquals(0, reader.position)
        assertEquals(0x0Eu.toUByte(), reader.readByte())
        assertEquals(1, reader.position)
    }

    @Test
    fun readingPastTheEndFailsRatherThanReturningGarbage() {
        val reader = ByteReader(TestData.bytes(0x01, 0x02))
        reader.readUShort()
        assertFalse(reader.hasBytesAvailable)
        assertFailsWith<FitFormatException> { reader.readByte() }
        assertFailsWith<FitFormatException> { reader.readBytes(1) }
        assertFailsWith<FitFormatException> { reader.skip(1) }
    }

    @Test
    fun anAttachedCrcChecksumsExactlyWhatWasConsumed() {
        val reader = ByteReader(TestData.fitFileShort)
        reader.crc = Crc()
        reader.skip(TestData.fitFileShort.size - 2)
        assertEquals(TestData.FIT_FILE_SHORT_CRC.toUShort(), reader.crc?.value)
    }

    @Test
    fun sliceCopiesWithoutMovingTheCursor() {
        val reader = ByteReader(TestData.fitFileShort)
        reader.skip(4)
        assertContentEquals(TestData.bytes(0x0E, 0x20), reader.slice(0, 2))
        assertEquals(4, reader.position)
    }

    @Test
    fun indexingReadsWithoutConsuming() {
        val reader = ByteReader(TestData.fitFileShort)
        assertEquals(0x2Eu.toUByte(), reader[8])
        assertEquals(0, reader.position)
        assertTrue(reader.hasBytesAvailable)
    }
}
