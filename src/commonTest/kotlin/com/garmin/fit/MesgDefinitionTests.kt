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

class MesgDefinitionTests {
    /** The definition record inside [TestData.fitFileShort], header byte first. */
    private fun readFixtureDefinition(): MesgDefinition {
        val reader = ByteReader(TestData.fitFileShort)
        reader.seek(14)
        val header = reader.readByte()
        return MesgDefinition.read(reader, header)
    }

    @Test
    fun readsTheLayoutOfARealDefinitionRecord() {
        val definition = readFixtureDefinition()

        assertEquals(0u.toUByte(), definition.localMesgNum)
        assertEquals(0u.toUShort(), definition.globalMesgNum) // file_id
        assertEquals(Endianness.LITTLE, definition.endianness)
        assertEquals(4, definition.numFields)
        assertEquals(0, definition.numDeveloperFields)

        assertEquals(BaseType.ENUM, definition.fieldDefinitions[0].baseType)
        assertEquals(BaseType.UINT16, definition.fieldDefinitions[1].baseType)
        assertEquals(BaseType.UINT32, definition.fieldDefinitions[2].baseType)
        assertEquals(BaseType.STRING, definition.fieldDefinitions[3].baseType)
    }

    @Test
    fun messageSizeIsTheSumOfTheFieldWidths() {
        val definition = readFixtureDefinition()
        assertEquals(1 + 2 + 4 + 10, definition.messageSize)
    }

    @Test
    fun aWrittenDefinitionReproducesTheOriginalBytes() {
        val definition = readFixtureDefinition()
        val writer = ByteWriter()
        definition.write(writer)
        assertContentEquals(TestData.fitFileShort.copyOfRange(14, 32), writer.toByteArray())
    }

    @Test
    fun fieldCountsAreDerivedFromTheWidthAndBaseType() {
        assertEquals(1, FieldDefinition(0u, 4, BaseType.UINT32).count)
        assertEquals(4, FieldDefinition(0u, 4, BaseType.UINT8).count)
        assertEquals(2, FieldDefinition(0u, 4, BaseType.UINT16).count)
    }

    @Test
    fun anUnknownTypeByteYieldsNoBaseType() {
        val definition = FieldDefinition(0u, 4, 0x7Fu)
        assertEquals(null, definition.baseType)
        assertEquals(0, definition.count)
    }

    @Test
    fun layoutEqualityIgnoresTheLocalNumberItIsBoundTo() {
        val fields = listOf(FieldDefinition(0u, 1, BaseType.ENUM))
        val a = MesgDefinition(0u, 20u, Endianness.LITTLE, fields)
        val b = MesgDefinition(3u, 20u, Endianness.LITTLE, fields)

        assertTrue(a.hasSameLayout(b))
        assertFalse(a == b)

        val c = MesgDefinition(0u, 21u, Endianness.LITTLE, fields)
        assertFalse(a.hasSameLayout(c))
    }

    @Test
    fun aDefinitionDerivedFromAMessageCoversItsPopulatedFields() {
        val mesg = Mesg("Test", 20u)
        val field = Field("value", 1u, BaseType.UINT16)
        field.setValue(42)
        mesg.setField(field)
        mesg.setField(Field("empty", 2u, BaseType.UINT8))

        val definition = MesgDefinition.of(mesg, localMesgNum = 5u)
        assertEquals(5u.toUByte(), definition.localMesgNum)
        assertEquals(20u.toUShort(), definition.globalMesgNum)
        // The field with no values contributes nothing to the record.
        assertEquals(1, definition.numFields)
        assertEquals(1u.toUByte(), definition.fieldDefinitions[0].num)
        assertEquals(2, definition.fieldDefinitions[0].size)
    }

    @Test
    fun aDefinitionSurvivesAWriteReadRoundTrip() {
        val original = MesgDefinition(
            localMesgNum = 2u,
            globalMesgNum = 20u,
            fieldDefinitions = listOf(
                FieldDefinition(253u, 4, BaseType.UINT32),
                FieldDefinition(0u, 4, BaseType.SINT32),
            ),
        )

        val writer = ByteWriter()
        original.write(writer)

        val reader = ByteReader(writer.toByteArray())
        val header = reader.readByte()
        val roundTripped = MesgDefinition.read(reader, header)

        assertEquals(original, roundTripped)
    }
}
