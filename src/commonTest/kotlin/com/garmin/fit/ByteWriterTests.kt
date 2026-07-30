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
import kotlin.test.assertTrue

class ByteWriterTests {
    @Test
    fun aNewWriterIsEmpty() {
        val writer = ByteWriter()
        assertTrue(writer.isEmpty)
        assertEquals(0, writer.size)
        assertContentEquals(ByteArray(0), writer.toByteArray())
    }

    @Test
    fun multiByteValuesHonourTheRequestedEndianness() {
        ByteWriter().let {
            it.writeUShort(0x0201u)
            assertContentEquals(TestData.bytes(0x01, 0x02), it.toByteArray())
        }
        ByteWriter().let {
            it.writeUShort(0x0201u, Endianness.BIG)
            assertContentEquals(TestData.bytes(0x02, 0x01), it.toByteArray())
        }
        ByteWriter().let {
            it.writeUInt(0x04030201u)
            assertContentEquals(TestData.bytes(0x01, 0x02, 0x03, 0x04), it.toByteArray())
        }
        ByteWriter().let {
            it.writeULong(0x0807060504030201uL, Endianness.BIG)
            assertContentEquals(TestData.bytes(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01), it.toByteArray())
        }
    }

    @Test
    fun stringsAreNulTerminated() {
        val writer = ByteWriter()
        writer.writeString("abc")
        assertContentEquals(TestData.bytes(0x61, 0x62, 0x63, 0x00), writer.toByteArray())
    }

    @Test
    fun theBufferGrowsPastItsInitialCapacity() {
        val writer = ByteWriter(initialCapacity = 2)
        repeat(1000) { writer.writeByte(0xABu) }
        assertEquals(1000, writer.size)
        assertTrue(writer.toByteArray().all { it == 0xAB.toByte() })
    }

    /** This is how the encoder patches the data size into an already-written header. */
    @Test
    fun replaceRangeOverwritesBytesInPlace() {
        val writer = ByteWriter()
        writer.writeBytes(ByteArray(14))
        writer.writeByte(0x42u)

        writer.replaceRange(0, TestData.bytes(0x0E, 0x20))
        val bytes = writer.toByteArray()
        assertEquals(0x0E.toByte(), bytes[0])
        assertEquals(0x20.toByte(), bytes[1])
        assertEquals(0x42.toByte(), bytes[14])
        assertEquals(15, writer.size)
    }

    @Test
    fun replaceRangeRefusesToWritePastWhatWasWritten() {
        val writer = ByteWriter()
        writer.writeBytes(ByteArray(4))
        assertFailsWith<IllegalArgumentException> { writer.replaceRange(2, ByteArray(4)) }
        assertFailsWith<IllegalArgumentException> { writer.replaceRange(-1, ByteArray(1)) }
    }

    @Test
    fun everythingWrittenReadsBackIdentically() {
        val writer = ByteWriter()
        writer.writeByte(0xFEu)
        writer.writeUShort(0xCAFEu)
        writer.writeUInt(0xDEADBEEFu)
        writer.writeULong(0x0123456789ABCDEFuL)
        writer.writeInt(-42)
        writer.writeFloat(1.5f)
        writer.writeDouble(-2.25)

        val reader = ByteReader(writer.toByteArray())
        assertEquals(0xFEu.toUByte(), reader.readByte())
        assertEquals(0xCAFEu.toUShort(), reader.readUShort())
        assertEquals(0xDEADBEEFu, reader.readUInt())
        assertEquals(0x0123456789ABCDEFuL, reader.readULong())
        assertEquals(-42, reader.readInt())
        assertEquals(1.5f, reader.readFloat())
        assertEquals(-2.25, reader.readDouble())
    }
}
