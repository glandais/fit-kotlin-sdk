/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

import com.garmin.fit.types.File
import com.garmin.fit.types.Manufacturer
import com.garmin.fit.types.Sport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EncoderTests {
    private fun fileId(): FileIdMesg = FileIdMesg().apply {
        type = File.ACTIVITY
        manufacturer = Manufacturer.GARMIN
        serialNumber = 1234u
    }

    @Test
    fun anEncodedFileIsAValidFitFile() {
        val bytes = encodeFit { write(fileId()) }

        assertTrue(FitDecoder.isFit(bytes))
        assertTrue(FitDecoder(bytes).checkIntegrity())
    }

    @Test
    fun theHeaderRecordsTheSizeOfWhatFollowsIt() {
        val bytes = encodeFit { write(fileId()) }
        val header = FileHeader.read(ByteReader(bytes))

        assertEquals(Fit.HEADER_WITH_CRC_SIZE, header.headerSize)
        assertEquals(bytes.size - Fit.HEADER_WITH_CRC_SIZE - Fit.CRC_SIZE, header.dataSize.toInt())
        assertEquals(Fit.PROFILE_VERSION, header.profileVersion)
        // The generated profile constant and the protocol constant are one number.
        assertEquals(Profile.VERSION, header.profileVersion)
    }

    /** [FitEncoder.close] fills in the header CRC, so the file passes the header check too. */
    @Test
    fun anEncodedFileCarriesAValidHeaderCrc() {
        val bytes = encodeFit { write(fileId()) }
        val header = FileHeader.read(ByteReader(bytes))

        assertTrue(header.hasCrc)
        assertEquals(Crc.calculate(bytes, 0, Fit.HEADER_WITHOUT_CRC_SIZE), header.headerCrc)
        assertTrue(FitDecoder(bytes).checkIntegrity())

        // Corrupting a header byte without touching the CRC breaks the check.
        val tampered = bytes.copyOf().also { it[1] = (it[1] + 1).toByte() }
        assertFalse(FitDecoder(tampered).checkIntegrity())
    }

    @Test
    fun whatIsWrittenIsWhatDecodes() {
        val bytes = encodeFit { write(fileId()) }
        val result = FitDecoder(bytes).decode()

        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertEquals(1, result.messages.fileIdMesgs.size)

        val decoded = result.messages.fileIdMesgs[0]
        assertEquals(File.ACTIVITY, decoded.type)
        assertEquals(Manufacturer.GARMIN, decoded.manufacturer)
        assertEquals(1234u, decoded.serialNumber)
    }

    /** A run of same-shaped messages should cost one definition, not one each. */
    @Test
    fun aRepeatedLayoutIsDefinedOnlyOnce() {
        val once = encodeFit {
            write(fileId())
            write(RecordMesg().apply { heartRate = 100u })
        }
        val threeRecords = encodeFit {
            write(fileId())
            write(RecordMesg().apply { heartRate = 100u })
            write(RecordMesg().apply { heartRate = 101u })
            write(RecordMesg().apply { heartRate = 102u })
        }

        // Each extra record adds only its 1-byte header plus its 1-byte value.
        assertEquals(once.size + 4, threeRecords.size)
    }

    @Test
    fun aChangedLayoutEmitsAFreshDefinition() {
        val bytes = encodeFit {
            write(fileId())
            write(RecordMesg().apply { heartRate = 100u })
            write(RecordMesg().apply { heartRate = 101u; cadence = 90u })
        }

        val result = FitDecoder(bytes).decode()
        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertEquals(2, result.messages.recordMesgs.size)
        assertEquals(90u.toUByte(), result.messages.recordMesgs[1].cadence)
    }

    @Test
    fun everyBaseTypeSurvivesARoundTrip() {
        val session = SessionMesg().apply {
            sport = Sport.CYCLING
            totalElapsedTime = 3661.5
            totalDistance = 42195.0
            totalCalories = 500u
            avgHeartRate = 145u
            maxSpeed = 12.345
        }

        val decoded = FitDecoder(encodeFit { write(fileId()); write(session) }).decode()
            .messages.sessionMesgs.single()

        assertEquals(Sport.CYCLING, decoded.sport)
        assertEquals(3661.5, decoded.totalElapsedTime)
        assertEquals(42195.0, decoded.totalDistance)
        assertEquals(500u.toUShort(), decoded.totalCalories)
        assertEquals(145u.toUByte(), decoded.avgHeartRate)
        assertNotNull(decoded.maxSpeed)
    }

    @Test
    fun stringsSurviveARoundTrip() {
        val decoded = FitDecoder(
            encodeFit {
                write(FileIdMesg().apply { type = File.ACTIVITY; productName = "a test device" })
            },
        ).decode().messages.fileIdMesgs.single()

        assertEquals("a test device", decoded.productName)
    }

    @Test
    fun anArrayFieldSurvivesARoundTrip() {
        val decoded = FitDecoder(
            encodeFit {
                write(fileId())
                write(RecordMesg().apply { compressedSpeedDistance = listOf(1u, 2u, 3u) })
            },
        ).decode(DecodeOptions(expandComponents = false)).messages.recordMesgs.single()

        assertEquals(listOf<UByte?>(1u, 2u, 3u), decoded.compressedSpeedDistance)
    }

    @Test
    fun anAbsentFieldIsNotWrittenAtAll() {
        val bytes = encodeFit { write(RecordMesg()) }
        val decoded = FitDecoder(bytes).decode().messages.recordMesgs.single()
        assertEquals(0, decoded.fieldList.size)
    }

    @Test
    fun manyMessagesEncodeAndDecode() {
        val bytes = encodeFit {
            write(fileId())
            repeat(1000) { i -> write(RecordMesg().apply { heartRate = (60 + i % 100).toUByte() }) }
        }

        val result = FitDecoder(bytes).decode()
        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertEquals(1000, result.messages.recordMesgs.size)
        assertEquals(60u.toUByte(), result.messages.recordMesgs[0].heartRate)
        assertEquals(159u.toUByte(), result.messages.recordMesgs[99].heartRate)
    }

    @Test
    fun anEncoderCannotBeUsedAfterClosing() {
        val encoder = FitEncoder()
        encoder.write(fileId())
        encoder.close()

        assertFailsWith<IllegalStateException> { encoder.write(fileId()) }
        assertFailsWith<IllegalStateException> { encoder.close() }
    }

    @Test
    fun theMessageCountTracksWhatWasWritten() {
        val encoder = FitEncoder()
        assertEquals(0, encoder.mesgCount)
        encoder.write(fileId())
        encoder.write(listOf(RecordMesg(), RecordMesg()))
        assertEquals(3, encoder.mesgCount)
        encoder.close()
    }

    /**
     * A big-endian encoder must announce BIG and then actually write big-endian,
     * so that its own decoder reads the values back unchanged.
     */
    @Test
    fun aBigEndianFileRoundTrips() {
        val bytes = encodeFit(Endianness.BIG) {
            write(fileId())
            write(RecordMesg().apply { heartRate = 140u; power = 250u; distance = 1000.0 })
        }

        // The first definition record starts right after the 14-byte header.
        assertEquals(Endianness.BIG.value, bytes[14 + 2].toUByte())

        val result = FitDecoder(bytes).decode()
        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertTrue(FitDecoder(bytes).checkIntegrity())

        val fileIdMesg = result.messages.fileIdMesgs.single()
        assertEquals(Manufacturer.GARMIN, fileIdMesg.manufacturer)
        assertEquals(1234u, fileIdMesg.serialNumber)

        val record = result.messages.recordMesgs.single()
        assertEquals(140u.toUByte(), record.heartRate)
        assertEquals(250u.toUShort(), record.power)
        assertEquals(1000.0, record.distance)
    }

    /** The two architectures differ in bytes but not in what they mean. */
    @Test
    fun bigAndLittleEndianEncodingsDecodeIdentically() {
        fun build(endianness: Endianness) = encodeFit(endianness) {
            write(fileId())
            write(RecordMesg().apply { power = 0x1234u; heartRate = 60u })
        }

        val little = build(Endianness.LITTLE)
        val big = build(Endianness.BIG)

        assertEquals(little.size, big.size)
        assertFalse(little.contentEquals(big))

        val fromLittle = FitDecoder(little).decode().messages.recordMesgs.single()
        val fromBig = FitDecoder(big).decode().messages.recordMesgs.single()
        assertEquals(0x1234u.toUShort(), fromLittle.power)
        assertEquals(fromLittle.power, fromBig.power)
        assertEquals(fromLittle.heartRate, fromBig.heartRate)
    }

    /**
     * A definition written explicitly keeps its architecture, and a later message
     * of the same shape under a different one gets its own definition record
     * rather than silently being read under the wrong architecture.
     */
    @Test
    fun anExplicitBigEndianDefinitionIsNotReusedByALittleEndianMessage() {
        val encoder = FitEncoder()
        val record = RecordMesg().apply { power = 0x1234u }

        encoder.write(MesgDefinition.of(record, localMesgNum = 0u, endianness = Endianness.BIG))
        encoder.write(fileId())
        encoder.write(record)
        val bytes = encoder.close()

        val result = FitDecoder(bytes).decode()
        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertEquals(0x1234u.toUShort(), result.messages.recordMesgs.single().power)
    }

    /** Two encoders given the same messages must produce the same bytes. */
    @Test
    fun encodingIsDeterministic() {
        fun build() = encodeFit {
            write(fileId())
            write(RecordMesg().apply { heartRate = 140u; cadence = 85u })
        }
        assertContentEquals(build(), build())
    }

    private fun stiffnessDescription(): DeveloperFieldDescription = DeveloperFieldDescription(
        applicationId = ByteArray(16) { it.toByte() },
        applicationVersion = 3u,
        developerDataIndex = 0u,
        fieldDefinitionNumber = 5u,
        fieldName = "leg_spring_stiffness",
        baseType = BaseType.UINT16,
        units = "kN/m",
        scale = 10.0,
    )

    @Test
    fun aRegisteredDeveloperFieldRoundTrips() {
        val description = stiffnessDescription()
        val bytes = encodeFit {
            write(fileId())
            registerDeveloperField(description)
            write(
                RecordMesg().apply {
                    heartRate = 120u
                    setDeveloperField(description.createField().apply { setValue(23.5) })
                },
            )
        }

        val result = FitDecoder(bytes).decode()
        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")

        // The declaration decodes back as it was registered...
        val decoded = result.messages.developerFieldDescriptions.single()
        assertEquals("leg_spring_stiffness", decoded.fieldName)
        assertEquals(BaseType.UINT16, decoded.baseType)
        assertEquals("kN/m", decoded.units)
        assertEquals(10.0, decoded.scale)
        assertContentEquals(description.applicationId, decoded.applicationId)
        assertEquals(3u, decoded.applicationVersion)

        // ...and gives the data record's extra bytes their meaning back.
        val field = result.messages.recordMesgs.single().developerFieldList.single()
        assertEquals("leg_spring_stiffness", field.fieldName)
        assertEquals("kN/m", field.units)
        assertEquals(23.5, field.getValue())
    }

    /** One `developer_data_id` claims the index; further fields only add descriptions. */
    @Test
    fun anApplicationAnnouncesItsIndexOnlyOnce() {
        val first = stiffnessDescription()
        val second = DeveloperFieldDescription(
            applicationId = first.applicationId,
            applicationVersion = 3u,
            developerDataIndex = 0u,
            fieldDefinitionNumber = 6u,
            fieldName = "form_power",
            baseType = BaseType.UINT16,
            units = "watts",
        )

        val bytes = encodeFit {
            write(fileId())
            registerDeveloperField(first)
            registerDeveloperField(second)
        }

        val result = FitDecoder(bytes).decode()
        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertEquals(1, result.messages.developerDataIdMesgs.size)
        assertEquals(2, result.messages.fieldDescriptionMesgs.size)
        assertEquals(2, result.messages.developerFieldDescriptions.size)
    }

    /** The decode → re-encode loop for developer fields, closed by hand. */
    @Test
    fun aDecodedDescriptionReRegistersIntoAReadableFile() {
        val description = stiffnessDescription()
        val original = encodeFit {
            write(fileId())
            registerDeveloperField(description)
            write(
                RecordMesg().apply {
                    setDeveloperField(description.createField().apply { setValue(21.0) })
                },
            )
        }
        val firstPass = FitDecoder(original).decode()
        assertTrue(firstPass.isSuccess)

        val reEncoded = encodeFit {
            write(fileId())
            firstPass.messages.developerFieldDescriptions.forEach { registerDeveloperField(it) }
            write(
                RecordMesg().apply {
                    firstPass.messages.recordMesgs.single().developerFieldList.forEach { setDeveloperField(DeveloperField(it)) }
                },
            )
        }

        val secondPass = FitDecoder(reEncoded).decode()
        assertTrue(secondPass.isSuccess, "unexpected errors: ${secondPass.errors}")
        val field = secondPass.messages.recordMesgs.single().developerFieldList.single()
        assertEquals("leg_spring_stiffness", field.fieldName)
        assertEquals(21.0, field.getValue())
    }
}
