/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

import com.garmin.fit.types.File
import com.garmin.fit.types.FitBaseType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /**
     * The 14-byte header carries its own CRC over the first 12 bytes, and
     * [FitDecoder.checkIntegrity] says it checks it. A file whose header CRC is
     * wrong but whose file CRC is right would otherwise pass unremarked.
     */
    @Test
    fun aWrongHeaderCrcFailsTheIntegrityCheck() {
        assertFalse(FitDecoder(TestData.fitFileShortInvalidHeaderCrc).checkIntegrity())

        // Only the header CRC is wrong: the records and the file CRC are intact,
        // so decoding still yields the message. checkIntegrity is the stricter
        // question, and the one that answers it.
        val result = FitDecoder(TestData.fitFileShortInvalidHeaderCrc).decode()
        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertEquals(1, result.messages.fileIdMesgs.size)
    }

    /** 0x0000 is how a producer says it never computed a header CRC; FIT allows it. */
    @Test
    fun anUnsetHeaderCrcIsAccepted() {
        assertTrue(FitDecoder(TestData.fitFileShortUnsetHeaderCrc).checkIntegrity())
        assertTrue(FitDecoder(TestData.fitFileShortUnsetHeaderCrc).decode().isSuccess)
    }

    /** The 12-byte header form has no header CRC at all, which is not a failure. */
    @Test
    fun aTwelveByteHeaderHasNoHeaderCrcToCheck() {
        val short = TestData.fitFileShortShortHeader
        assertEquals(Fit.HEADER_WITHOUT_CRC_SIZE, FileHeader.read(ByteReader(short)).headerSize)
        assertFalse(FileHeader.read(ByteReader(short)).hasCrc)

        assertTrue(FitDecoder(short).isFit())
        assertTrue(FitDecoder(short).checkIntegrity())
        assertEquals(1, FitDecoder(short).decode().messages.fileIdMesgs.size)
    }

    /** Every file in a chain gets its header checked, not just the first. */
    @Test
    fun aBadHeaderCrcAnywhereInAChainIsCaught() {
        assertFalse(
            FitDecoder(TestData.fitFileShortInvalidHeaderCrc + TestData.fitFileShort).checkIntegrity(),
            "a bad header CRC on the first file went unnoticed",
        )
        assertFalse(
            FitDecoder(TestData.fitFileShort + TestData.fitFileShortInvalidHeaderCrc).checkIntegrity(),
            "a bad header CRC on the second file went unnoticed",
        )
        assertTrue(FitDecoder(TestData.fitFileShort + TestData.fitFileShort).checkIntegrity())
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
     * whatever the options are, no [Exception] escapes [FitDecoder.decode].
     *
     * An [Error] is deliberately not caught here either — the decoder no longer
     * swallows one, so letting it out of the test is the right outcome: it means
     * the VM is in trouble, not that this file is malformed.
     */
    @Test
    fun noExceptionEscapesOnArbitraryMalformedInput() {
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
            } catch (e: Exception) {
                fail("${e::class.simpleName}: ${e.message} escaped on ${hex(mutated)} in ${options.mode}")
            }
        }
    }

    /**
     * The safety net is scoped to what a bad file can cause.
     *
     * A caller's own failure inside [FitDecoder.read] is not a file error and
     * must come back out untouched, whether it is an [Exception] or an [Error].
     * That is the same boundary the decoder's internal net now draws: it turns
     * an unforeseen [Exception] into a [FitError] and lets an [Error] through.
     */
    @Test
    fun theSafetyNetDoesNotSwallowTheCallersOwnFailures() {
        assertFailsWith<IllegalStateException> {
            FitDecoder(TestData.fitFileShort).read { error("the caller's own problem") }
        }
        assertFailsWith<OutOfMemoryError> {
            FitDecoder(TestData.fitFileShort).read { throw OutOfMemoryError("not a decode error") }
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

    // ---------------------------------------------------- low-level records

    /** Replaces the last two bytes of [bytes] with the file CRC over the rest. */
    private fun withFileCrc(bytes: ByteArray): ByteArray {
        val crc = Crc.calculate(bytes, 0, bytes.size - Fit.CRC_SIZE)
        bytes[bytes.size - 2] = (crc.toInt() and 0xFF).toByte()
        bytes[bytes.size - 1] = ((crc.toInt() shr 8) and 0xFF).toByte()
        return bytes
    }

    /** A minimal, otherwise well-formed file whose single record is [recordBytes]. */
    private fun fileWithRecord(recordBytes: ByteArray): ByteArray {
        val header = FileHeader(dataSize = recordBytes.size.toUInt())
        header.updateCrc()
        return withFileCrc(header.toByteArray() + recordBytes + ByteArray(Fit.CRC_SIZE))
    }

    /**
     * A data record naming a local message number no definition has claimed —
     * whether none was ever sent, or the file simply opens with a data record —
     * is a format error, not a crash, and it must not derail messages already
     * decoded before it.
     */
    @Test
    fun aDataRecordForAnUndefinedLocalMessageIsReportedNotThrown() {
        // Header byte 0x00: a plain data record for local message 0, which
        // nothing has defined yet.
        val bytes = fileWithRecord(TestData.bytes(0x00))

        val result = FitDecoder(bytes).decode()
        assertFalse(result.isSuccess)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].message.contains("local message"), result.errors[0].message)
        assertEquals(0, result.mesgs.size)

        // The rest of a longer file still comes back: a first, well-defined
        // message is kept even though the second record fails.
        val whole = TestData.fitFileShort
        val truncatedThenBad = fileWithRecord(
            whole.copyOfRange(14, whole.size - Fit.CRC_SIZE) + TestData.bytes(0x0F),
        )
        val partial = FitDecoder(truncatedThenBad).decode()
        assertFalse(partial.isSuccess)
        assertEquals(1, partial.messages.fileIdMesgs.size, "the message read before the bad record was lost")
    }

    /**
     * Bit 7 of a record header marks a compressed-timestamp data record, a
     * layout this SDK does not support. [MesgCursor] rejects it outright rather
     * than misreading the following bytes as an ordinary record.
     */
    @Test
    fun aCompressedTimestampHeaderIsRejected() {
        val bytes = fileWithRecord(TestData.bytes(0x80))

        val result = FitDecoder(bytes).decode()
        assertFalse(result.isSuccess)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].message.contains("compressed"), result.errors[0].message)
        assertEquals(0, result.mesgs.size)

        // Every top bit set still reads as compressed, definition bit or not.
        assertFalse(FitDecoder(fileWithRecord(TestData.bytes(0xFF))).decode().isSuccess)
    }

    /**
     * A field definition whose declared size is not a whole multiple of its base
     * type's width means the producer and the profile disagree about this field.
     * [FieldBase.read] skips exactly that many bytes to keep the record aligned,
     * so the field itself is dropped but every field after it decodes normally.
     */
    @Test
    fun aFieldSizeThatIsNotAWholeMultipleOfItsBaseTypeIsSkippedWithoutDerailingTheRest() {
        val definition = MesgDefinition(
            localMesgNum = 0u,
            globalMesgNum = 65280u, // an unknown message number, decoded generically
            fieldDefinitions = listOf(
                FieldDefinition(0u, 3, BaseType.UINT32), // 3 bytes: not a multiple of 4
                FieldDefinition(1u, 4, BaseType.UINT32),
            ),
        )
        val defWriter = ByteWriter()
        definition.write(defWriter)

        val dataWriter = ByteWriter()
        dataWriter.writeByte(0u) // data record header, local message 0
        dataWriter.writeBytes(TestData.bytes(0x01, 0x02, 0x03)) // misaligned field: 3 garbage bytes
        dataWriter.writeUInt(0x12345678u) // well-formed field right after it

        val bytes = fileWithRecord(defWriter.toByteArray() + dataWriter.toByteArray())

        val result = FitDecoder(bytes).decode(DecodeOptions(includeUnknownData = true))
        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")

        val mesg = result.messages.unknownMesgs.single()
        assertNull(mesg.getField(0u), "the misaligned field must not survive decoding")
        assertEquals(0x12345678u, mesg.getField(1u)?.getValue(), "the field after it must decode intact")
    }

    // ------------------------------------------------------ developer data

    private fun developerDataId(uuidByte: Int) = DeveloperDataIdMesg().apply {
        developerDataIndex = 0u
        applicationId = List(16) { uuidByte.toUByte() }
    }

    private fun fieldDescription(name: String) = FieldDescriptionMesg().apply {
        developerDataIndex = 0u
        fieldDefinitionNumber = 0u
        fitBaseTypeId = FitBaseType.UINT8
        fieldName = listOf(name)
    }

    /**
     * Nothing stops two applications in one stream from each claiming developer
     * data index 0, which is exactly why [DeveloperDataKey] carries the
     * application UUID. The lookup has to carry it too: keyed on the index
     * alone, the second application's records would be read under the first
     * one's declarations and come out with the wrong name, units and scale.
     */
    @Test
    fun aDeveloperFieldDeclarationBelongsToTheApplicationThatMadeIt() {
        val lookup = DeveloperDataLookup()

        lookup.addDeveloperDataId(developerDataId(1))
        assertNotNull(lookup.addFieldDescription(fieldDescription("doughnuts_earned")))
        assertEquals("doughnuts_earned", lookup.getFieldDescription(0u, 0u)?.fieldName)

        // A second application takes over index 0 and declares nothing.
        lookup.addDeveloperDataId(developerDataId(2))
        assertNull(
            lookup.getFieldDescription(0u, 0u),
            "the first application's declaration described the second application's field",
        )

        // Its own declaration is found, and is a different one.
        assertNotNull(lookup.addFieldDescription(fieldDescription("calories_burned")))
        assertEquals("calories_burned", lookup.getFieldDescription(0u, 0u)?.fieldName)

        // Both declarations were kept: the file did declare two fields.
        assertEquals(2, lookup.fieldDescriptions.size)
        assertNotEquals(lookup.fieldDescriptions[0].key, lookup.fieldDescriptions[1].key)
    }

    /** With no `developer_data_id` at all the key is still consistent both ways. */
    @Test
    fun anUnclaimedDeveloperDataIndexStillResolvesItsOwnDeclarations() {
        val lookup = DeveloperDataLookup()
        assertNotNull(lookup.addFieldDescription(fieldDescription("undeclared_app")))

        val description = lookup.getFieldDescription(0u, 0u)
        assertNotNull(description)
        assertEquals("undeclared_app", description.fieldName)
        assertNull(description.applicationId)
    }

    /**
     * The format says `developer_data_id` comes first, and a file that puts it
     * after its `field_description` used to decode anyway. Keying the lookup on
     * the application must not cost that: the declaration was registered under
     * no application, and it is still the only candidate for the index.
     */
    @Test
    fun aDeclarationMadeBeforeItsApplicationIdStillResolves() {
        val lookup = DeveloperDataLookup()

        assertNotNull(lookup.addFieldDescription(fieldDescription("doughnuts_earned")))
        lookup.addDeveloperDataId(developerDataId(1))

        assertEquals("doughnuts_earned", lookup.getFieldDescription(0u, 0u)?.fieldName)
    }
}
