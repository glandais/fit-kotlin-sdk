/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

/**
 * FIT byte sequences shared by the tests, annotated with their record structure.
 *
 * These are the same fixtures the Swift and JavaScript SDKs use, so a behaviour
 * change here shows up as a difference against those SDKs rather than as a
 * silently rebaselined expectation.
 */
internal object TestData {
    fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    /** A complete one-message file: file_id with type, manufacturer, serial and a string. */
    val fitFileShort: ByteArray = bytes(
        0x0E, 0x20, 0x8B, 0x08, 0x24, 0x00, 0x00, 0x00, 0x2E, 0x46, 0x49, 0x54, 0x8E, 0xA3, // header, 14 bytes
        0x40, 0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x01, 0x02, 0x84, 0x04, 0x04, 0x86, 0x08, 0x0A, 0x07, // definition, 18 bytes
        0x00, 0x04, 0x01, 0x00, 0x00, 0xCA, 0x9A, 0x3B, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x00, // data, 18 bytes
        0x5D, 0xF2, // file CRC
    )

    /** [fitFileShort] with its header zeroed out. */
    val fitFileShortInvalidHeader: ByteArray = bytes(
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x40, 0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x01, 0x02, 0x84, 0x04, 0x04, 0x86, 0x08, 0x0A, 0x07,
        0x00, 0x04, 0x01, 0x00, 0x00, 0xCA, 0x9A, 0x3B, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x00,
        0x5D, 0xF2,
    )

    /** [fitFileShort] with the file CRC corrupted. */
    val fitFileShortInvalidCrc: ByteArray = fitFileShort.copyOf().also {
        it[it.size - 1] = (it[it.size - 1] + 1).toByte()
    }

    /** The records of [fitFileShort] with no header and no CRC. */
    val fitFileShortDataOnly: ByteArray = fitFileShort.copyOfRange(14, fitFileShort.size - 2)

    /** CRC over everything in [fitFileShort] but its trailing CRC. */
    const val FIT_FILE_SHORT_CRC: Int = 0xF25D
}
