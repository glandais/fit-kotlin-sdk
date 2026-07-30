/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

import com.garmin.fit.types.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

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

    /**
     * A file cut short of its two closing CRC bytes is truncated, and NORMAL
     * mode has to say so: silently accepting it would report a partial activity
     * as a whole one.
     */
    @Test
    fun aFileTruncatedBeforeItsCrcIsReported() {
        val whole = TestData.fitFileShort
        for (missing in 1..Fit.CRC_SIZE) {
            val input = whole.copyOfRange(0, whole.size - missing)
            val result = FitDecoder(input).decode()

            assertFalse(result.isSuccess, "$missing byte(s) short of the CRC decoded as a whole file")
            assertEquals(1, result.errors.size, "errors for a $missing byte truncation: ${result.errors}")
            assertTrue(result.errors[0].message.contains("CRC"), result.errors[0].message)
            // Whatever decoded before the truncation is still handed back.
            assertEquals(1, result.messages.fileIdMesgs.size)

            assertFalse(FitDecoder(input).checkIntegrity(), "integrity held for a $missing byte truncation")
            assertEquals(1, FitDecoder(input).read { }.size)
        }

        // The untouched fixture stays acceptable.
        val result = FitDecoder(whole).decode()
        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertTrue(FitDecoder(whole).checkIntegrity())
    }

    /** In a chained stream only the last file can be missing its CRC. */
    @Test
    fun aChainedStreamTruncatedBeforeItsLastCrcIsReported() {
        val chained = TestData.fitFileShort + TestData.fitFileShort
        val input = chained.copyOfRange(0, chained.size - Fit.CRC_SIZE)
        val result = FitDecoder(input).decode()

        assertFalse(result.isSuccess)
        assertTrue(result.errors[0].message.contains("CRC"), result.errors[0].message)
        // Both files' records were complete; only the closing CRC was cut.
        assertEquals(2, result.messages.fileIdMesgs.size)
    }

    /** Only NORMAL mode expects a file CRC; the other two must not gain an error. */
    @Test
    fun aMissingCrcIsNotAnErrorOutsideNormalMode() {
        val truncated = TestData.fitFileShort.copyOfRange(0, TestData.fitFileShort.size - Fit.CRC_SIZE)

        val skipped = FitDecoder(truncated).decode(DecodeOptions(mode = DecodeMode.SKIP_HEADER))
        assertTrue(skipped.isSuccess, "unexpected errors: ${skipped.errors}")
        assertEquals(1, skipped.messages.fileIdMesgs.size)

        // DATA_ONLY input carries no header and no CRC at all.
        val dataOnly = FitDecoder(TestData.fitFileShortDataOnly).decode(DecodeOptions(mode = DecodeMode.DATA_ONLY))
        assertTrue(dataOnly.isSuccess, "unexpected errors: ${dataOnly.errors}")
        assertEquals(1, dataOnly.messages.fileIdMesgs.size)
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

    /**
     * SKIP_HEADER trusts nothing the header says, including its own size, so a
     * stream shorter than the header it declares must come back as a result.
     */
    @Test
    fun everyTruncationOfAFileDecodesInSkipHeaderMode() {
        for (length in 0..TestData.fitFileShort.size) {
            val input = TestData.fitFileShort.copyOfRange(0, length)
            val result = FitDecoder(input).decode(DecodeOptions(mode = DecodeMode.SKIP_HEADER))
            assertNotNull(result, "no result for a $length byte stream")
        }
    }

    /** Out-of-range seeks are file errors, not programming errors. */
    @Test
    fun seekingOutsideTheInputRaisesAFitFormatException() {
        val reader = ByteReader(TestData.fitFileShort)

        assertFailsWith<FitFormatException> { reader.seek(-1) }
        assertFailsWith<FitFormatException> { reader.seek(TestData.fitFileShort.size + 1) }
        assertFailsWith<FitFormatException> { reader.skip(-1) }
        assertEquals(0, reader.position, "a rejected seek must not move the cursor")

        reader.seek(TestData.fitFileShort.size)
        assertFalse(reader.hasBytesAvailable)
    }

    /**
     * The contract the rest of the SDK is built on: whatever the bytes are, and
     * whatever the options are, nothing escapes [FitDecoder.decode].
     */
    @Test
    fun noThrowableEscapesOnArbitraryMalformedInput() {
        val random = Random(20260730)
        val modes = listOf(DecodeMode.NORMAL, DecodeMode.SKIP_HEADER, DecodeMode.DATA_ONLY)

        for (i in 0 until 2000) {
            val mutated = TestData.fitFileShort.copyOf(random.nextInt(TestData.fitFileShort.size + 1))
            repeat(if (mutated.isEmpty()) 0 else 1 + random.nextInt(4)) {
                mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte()
            }
            val options = DecodeOptions(mode = modes[i % modes.size], includeUnknownData = true)

            try {
                FitDecoder(mutated).decode(options)
                FitDecoder(mutated).read(options) { }
                FitDecoder(mutated).checkIntegrity()
                FitDecoder(mutated).isFit()
            } catch (e: Throwable) {
                fail("${e::class.simpleName}: ${e.message} escaped on ${hex(mutated)} in ${options.mode}")
            }
        }
    }

    /** An unreadable file yields errors, never an exception, through [FitDecoder.read] too. */
    @Test
    fun theCallbackApiReportsFailuresInsteadOfThrowing() {
        val errors = FitDecoder(TestData.fitFileShortInvalidCrc).read { }
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("CRC"), errors[0].message)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") {
        val v = it.toInt() and 0xFF
        "0123456789ABCDEF"[v shr 4].toString() + "0123456789ABCDEF"[v and 0x0F]
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
