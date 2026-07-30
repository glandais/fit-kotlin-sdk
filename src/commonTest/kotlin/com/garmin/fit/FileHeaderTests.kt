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
import kotlin.test.assertTrue

class FileHeaderTests {
    @Test
    fun readsTheFieldsOfARealHeader() {
        val header = FileHeader.read(ByteReader(TestData.fitFileShort))

        assertEquals(14, header.headerSize)
        assertEquals(ProtocolVersion.V2_0, header.protocolVersion)
        assertEquals(0x088Bu.toUShort(), header.profileVersion)
        assertEquals(0x24u, header.dataSize)
        assertEquals(FileHeader.FIT_DATA_TYPE, header.dataType)
        assertTrue(header.hasCrc)
        assertTrue(header.isValid)
    }

    @Test
    fun aZeroedHeaderIsNotValid() {
        val header = FileHeader.read(ByteReader(TestData.fitFileShortInvalidHeader))
        assertFalse(header.isValid)
    }

    @Test
    fun theHeaderCrcCoversTheFirstTwelveBytesOnly() {
        val header = FileHeader.read(ByteReader(TestData.fitFileShort))
        val recorded = header.headerCrc

        header.updateCrc()
        assertEquals(recorded, header.headerCrc)
        assertEquals(Crc.calculate(TestData.fitFileShort, 0, 12), header.headerCrc)
    }

    @Test
    fun theTwelveByteFormOmitsTheCrc() {
        val header = FileHeader(headerSize = Fit.HEADER_WITHOUT_CRC_SIZE, dataSize = 100u)
        assertFalse(header.hasCrc)
        assertTrue(header.isValid)
        assertEquals(12, header.toByteArray().size)
    }

    @Test
    fun aWrittenHeaderReadsBackIdentically() {
        val header = FileHeader(dataSize = 0x1234u)
        header.updateCrc()

        val writer = ByteWriter()
        header.write(writer)
        val bytes = writer.toByteArray()
        assertEquals(14, bytes.size)

        val roundTripped = FileHeader.read(ByteReader(bytes))
        assertEquals(header.headerSize, roundTripped.headerSize)
        assertEquals(header.protocolVersion, roundTripped.protocolVersion)
        assertEquals(header.profileVersion, roundTripped.profileVersion)
        assertEquals(header.dataSize, roundTripped.dataSize)
        assertEquals(header.dataType, roundTripped.dataType)
        assertEquals(header.headerCrc, roundTripped.headerCrc)
    }

    @Test
    fun theFixtureHeaderSerialisesBackToItsOriginalBytes() {
        val header = FileHeader.read(ByteReader(TestData.fitFileShort))
        assertContentEquals(TestData.fitFileShort.copyOfRange(0, 14), header.toByteArray())
    }

    @Test
    fun defaultsMatchTheProfileThisSdkWasGeneratedFrom() {
        val header = FileHeader()
        assertEquals(ProtocolVersion.V2_0, header.protocolVersion)
        assertEquals(Fit.PROFILE_VERSION, header.profileVersion)
        assertEquals(0u, header.dataSize)
    }
}
