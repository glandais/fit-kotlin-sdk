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
import kotlin.test.assertTrue

/**
 * Decodes a real file and encodes it straight back.
 *
 * This is the broadest check in the suite: one round trip exercises the header,
 * both CRCs, definition emission and reuse, field ordering, the base types the
 * file declared, string padding and every invalid sentinel at once. In
 * particular it is what catches an absent float being written back as a
 * canonical NaN rather than the all-ones pattern the file held.
 *
 * One fixture reproduces byte for byte. The others come back a few bytes
 * shorter because they emit a message definition the encoder finds is already
 * bound to a local message number and so does not repeat. No decoded data
 * differs, which is what [aRecordedActivityRoundTrips] asserts field by field.
 */
class ReEncodeTests {
    /** Re-encodes [bytes], preserving the header fields that describe the producer. */
    private fun reEncode(bytes: ByteArray): ByteArray {
        val header = FileHeader.read(ByteReader(bytes))

        // No component expansion: expanded fields are derived from others, and
        // writing them back would add fields the original file never carried.
        val result = FitDecoder(bytes).decode(
            DecodeOptions(expandComponents = false, mergeHeartRates = false, includeUnknownData = true),
        )
        assertTrue(result.isSuccess, "decode failed: ${result.errors}")

        return FitEncoder(
            protocolVersionByte = header.protocolVersionByte,
            profileVersion = header.profileVersion,
        ).write(result.mesgs).close()
    }

    private fun assertRoundTrips(original: ByteArray) {
        val reEncoded = reEncode(original)

        assertTrue(FitDecoder.isFit(reEncoded))
        assertTrue(FitDecoder(reEncoded).checkIntegrity(), "re-encoded file fails its own CRC")

        val opts = DecodeOptions(expandComponents = false, mergeHeartRates = false, includeUnknownData = true)
        val before = FitDecoder(original).decode(opts)
        val after = FitDecoder(reEncoded).decode(opts)

        assertEquals(before.mesgs.size, after.mesgs.size, "message count changed")

        for ((a, b) in before.mesgs.zip(after.mesgs)) {
            assertEquals(a.mesgName, b.mesgName)
            assertEquals(a.globalMesgNum, b.globalMesgNum)
            assertEquals(
                a.fieldList.map { it.fieldNum },
                b.fieldList.map { it.fieldNum },
                "field order changed on ${a.mesgName}",
            )
            for ((fa, fb) in a.fieldList.zip(b.fieldList)) {
                assertEquals(fa.toList(), fb.toList(), "${a.mesgName}.${fa.fieldName} changed")
            }
        }
    }

    /**
     * The strongest assertion available: this fixture comes back out exactly as
     * it went in, all 74645 bytes.
     */
    @Test
    fun aFileWithGearChangeDataReEncodesToIdenticalBytes() {
        val original = TestFixtures.withGearChangeData
        assertContentEquals(original, reEncode(original))
    }

    @Test
    fun aRecordedActivityRoundTrips() {
        assertRoundTrips(TestFixtures.activity)
    }

    @Test
    fun aFileWithDeveloperFieldsRoundTripsItsProfileFields() {
        assertRoundTrips(TestFixtures.activityDevFields)
    }

    @Test
    fun aFileWithHeartRateMessagesRoundTrips() {
        assertRoundTrips(TestFixtures.hrmPluginTestActivity)
    }

    /** Re-encoding must never grow a file: that would mean fields were invented. */
    @Test
    fun reEncodingDoesNotGrowAFile() {
        for (original in listOf(TestFixtures.activity, TestFixtures.withGearChangeData, TestFixtures.hrmPluginTestActivity)) {
            assertTrue(reEncode(original).size <= original.size, "re-encoded file is larger than the original")
        }
    }

    /** The header describes the producer, so it has to survive a round trip. */
    @Test
    fun theHeaderIsReproducedWhenItIsPreserved() {
        val original = TestFixtures.withGearChangeData
        val before = FileHeader.read(ByteReader(original))
        val after = FileHeader.read(ByteReader(reEncode(original)))

        assertEquals(before.protocolVersionByte, after.protocolVersionByte)
        assertEquals(before.profileVersion, after.profileVersion)
        assertEquals(before.dataType, after.dataType)
    }
}
