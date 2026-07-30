/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

import kotlin.test.Test
import kotlin.test.assertEquals

class CrcTests {
    @Test
    fun incrementalUpdateMatchesTheFilesRecordedCrc() {
        val crc = Crc()
        for (i in 0 until TestData.fitFileShort.size - 2) {
            crc.update(TestData.fitFileShort[i])
        }
        assertEquals(TestData.FIT_FILE_SHORT_CRC.toUShort(), crc.value)
    }

    @Test
    fun oneShotCalculationMatchesTheFilesRecordedCrc() {
        val crc = Crc.calculate(TestData.fitFileShort, 0, TestData.fitFileShort.size - 2)
        assertEquals(TestData.FIT_FILE_SHORT_CRC.toUShort(), crc)
    }

    @Test
    fun crcOfNoBytesIsZero() {
        assertEquals(0u.toUShort(), Crc.calculate(ByteArray(0)))
        assertEquals(0u.toUShort(), Crc().value)
    }

    @Test
    fun resetReturnsTheCalculatorToItsInitialState() {
        val crc = Crc()
        crc.update(TestData.fitFileShort)
        crc.reset()
        assertEquals(0u.toUShort(), crc.value)
    }
}
