/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

import com.garmin.fit.types.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DecoderTests {
    @Test
    fun aWellFormedFileIsRecognisedAsFit() {
        assertTrue(FitDecoder(TestData.fitFileShort).isFit())
        assertTrue(FitDecoder.isFit(TestData.fitFileShort))
    }

    @Test
    fun anythingElseIsNot() {
        assertFalse(FitDecoder(ByteArray(0)).isFit())
        assertFalse(FitDecoder(TestData.bytes(0x01, 0x02, 0x03)).isFit())
        assertFalse(FitDecoder(TestData.fitFileShortInvalidHeader).isFit())
        // Header claims more data than the file holds.
        assertFalse(FitDecoder(TestData.fitFileShort.copyOfRange(0, 20)).isFit())
    }

    @Test
    fun integrityFollowsTheRecordedCrc() {
        assertTrue(FitDecoder(TestData.fitFileShort).checkIntegrity())
        assertFalse(FitDecoder(TestData.fitFileShortInvalidCrc).checkIntegrity())
        assertFalse(FitDecoder(TestData.fitFileShortInvalidHeader).checkIntegrity())
    }

    @Test
    fun decodingTheFixtureYieldsItsSingleFileIdMessage() {
        val result = FitDecoder(TestData.fitFileShort).decode()

        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertEquals(1, result.mesgs.size)
        assertEquals(1, result.messages.fileIdMesgs.size)

        val fileId = result.messages.fileIdMesgs[0]
        assertEquals(File.ACTIVITY, fileId.type)
        assertEquals(1u.toUShort(), fileId.manufacturer?.value)
        assertEquals("abcdefghi", fileId.productName)
        assertNotNull(fileId.timeCreated)
        assertEquals(1000000000u, fileId.getFieldValue(FileIdMesg.TIME_CREATED_FIELD_NUM))
    }

    @Test
    fun theProfileVersionComesFromTheFileHeader() {
        val result = FitDecoder(TestData.fitFileShort).decode()
        assertEquals(0x088Bu.toUShort(), result.profileVersion)
    }

    /** A corrupt file must still hand back what decoded before the problem. */
    @Test
    fun aBadCrcIsReportedWithoutLosingTheDecodedMessages() {
        val result = FitDecoder(TestData.fitFileShortInvalidCrc).decode()

        assertFalse(result.isSuccess)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].message.contains("CRC"), result.errors[0].message)
        assertEquals(1, result.messages.fileIdMesgs.size)
    }

    @Test
    fun decodingNeverThrows() {
        // Random bytes, a truncated file, and an empty input all come back as
        // results rather than exceptions.
        for (input in listOf(ByteArray(0), TestData.bytes(0xFF, 0xFE, 0xFD), TestData.fitFileShort.copyOfRange(0, 30))) {
            val result = FitDecoder(input).decode()
            assertNotNull(result)
        }
    }

    @Test
    fun aBadHeaderIsRejectedInNormalModeAndToleratedInSkipHeaderMode() {
        val decoder = FitDecoder(TestData.fitFileShortInvalidHeader)

        assertFalse(decoder.decode(DecodeOptions(mode = DecodeMode.NORMAL)).isSuccess)

        val skipped = decoder.decode(DecodeOptions(mode = DecodeMode.SKIP_HEADER))
        assertEquals(1, skipped.messages.fileIdMesgs.size)
    }

    @Test
    fun headerlessRecordsDecodeInDataOnlyMode() {
        val result = FitDecoder(TestData.fitFileShortDataOnly).decode(DecodeOptions(mode = DecodeMode.DATA_ONLY))

        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertEquals(1, result.messages.fileIdMesgs.size)
    }

    @Test
    fun headerlessRecordsAreNotAFitFileInNormalMode() {
        val result = FitDecoder(TestData.fitFileShortDataOnly).decode()
        assertFalse(result.isSuccess)
    }

    @Test
    fun twoConcatenatedFilesBothDecode() {
        val chained = TestData.fitFileShort + TestData.fitFileShort
        val result = FitDecoder(chained).decode()

        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertEquals(2, result.messages.fileIdMesgs.size)
    }

    @Test
    fun messagesCarryTheirPositionInTheFile() {
        val chained = TestData.fitFileShort + TestData.fitFileShort
        val result = FitDecoder(chained).decode()
        assertEquals(listOf(0, 1), result.mesgs.map { it.decoderMesgIndex })
    }

    @Test
    fun theSequenceApiDecodesLazily() {
        val chained = TestData.fitFileShort + TestData.fitFileShort
        val first = FitDecoder(chained).asSequence().first()
        assertEquals(Profile.MesgNum.FILE_ID, first.globalMesgNum)
    }

    @Test
    fun theSequenceApiThrowsWhereTheResultApiCollects() {
        assertFailsWith<FitFormatException> {
            FitDecoder(TestData.fitFileShortInvalidCrc).asSequence().toList()
        }
    }

    @Test
    fun theCallbackApiSeesEveryMessage() {
        val seen = mutableListOf<Mesg>()
        val errors = FitDecoder(TestData.fitFileShort).read { seen.add(it) }

        assertTrue(errors.isEmpty())
        assertEquals(1, seen.size)
    }

    /** Only fields the record actually carried should show up as present. */
    @Test
    fun aDecodedMessageHoldsOnlyThePopulatedFields() {
        val fileId = FitDecoder(TestData.fitFileShort).decode().messages.fileIdMesgs[0]
        assertEquals(4, fileId.fieldList.size)
    }
}
