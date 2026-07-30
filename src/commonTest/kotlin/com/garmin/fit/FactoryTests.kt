/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FactoryTests {
    @Test
    fun aProfileMessageComesBackWithItsNameAndEveryField() {
        val mesg = Factory.createMesg(Profile.MesgNum.RECORD)

        assertEquals("Record", mesg.mesgName)
        assertEquals(Profile.MesgNum.RECORD, mesg.globalMesgNum)
        assertTrue(mesg.fieldList.size > 20)
        assertNotNull(mesg.getField(RecordMesg.HEART_RATE_FIELD_NUM))
    }

    @Test
    fun anUnknownMessageNumberYieldsAnEmptyMessage() {
        val mesg = Factory.createMesg(0xFFF0u)
        assertEquals(Factory.UNKNOWN_NAME, mesg.mesgName)
        assertEquals(0, mesg.fieldList.size)
        assertFalse(Factory.isKnownMesg(0xFFF0u))
        assertTrue(Factory.isKnownMesg(Profile.MesgNum.RECORD))
    }

    @Test
    fun fieldsCarryTheirProfileMetadata() {
        val altitude = Factory.createField(Profile.MesgNum.RECORD, RecordMesg.ALTITUDE_FIELD_NUM)
        assertNotNull(altitude)
        assertEquals("Altitude", altitude.fieldName)
        assertEquals(BaseType.UINT16, altitude.baseType)
        assertEquals(5.0, altitude.scale)
        assertEquals(500.0, altitude.offset)
        assertEquals("m", altitude.units)
    }

    @Test
    fun anUnknownFieldNumberYieldsNull() {
        assertNull(Factory.createField(Profile.MesgNum.RECORD, 250u))
        assertNull(Factory.createField(0xFFF0u, 1u))
    }

    /** Handing out the prototype itself would let one decoded file corrupt the next. */
    @Test
    fun everyCallReturnsAFreshField() {
        val first = Factory.createField(Profile.MesgNum.RECORD, RecordMesg.HEART_RATE_FIELD_NUM)
        val second = Factory.createField(Profile.MesgNum.RECORD, RecordMesg.HEART_RATE_FIELD_NUM)
        assertNotNull(first)
        assertNotNull(second)
        assertNotSame(first, second)

        first.setValue(120)
        assertFalse(second.hasValues)
    }

    @Test
    fun aDefaultFieldTakesItsTypeFromTheFile() {
        val field = Factory.createDefaultField(42u, BaseType.UINT16)
        assertEquals(Factory.UNKNOWN_NAME, field.fieldName)
        assertEquals(42u.toUByte(), field.fieldNum)
        assertEquals(BaseType.UINT16, field.baseType)
        assertEquals(Fit.FIELD_DEFAULT_SCALE, field.scale)
    }

    @Test
    fun componentsAndSubfieldsSurviveIntoTheCreatedField() {
        // altitude packs into enhanced_altitude.
        val altitude = Factory.createField(Profile.MesgNum.RECORD, RecordMesg.ALTITUDE_FIELD_NUM)
        assertNotNull(altitude)
        assertTrue(altitude.hasComponents)

        // event_data means different things depending on the event field.
        val eventData = Factory.createField(Profile.MesgNum.EVENT, EventMesg.DATA_FIELD_NUM)
        assertNotNull(eventData)
        assertTrue(eventData.hasSubFields)
        assertTrue(eventData.subFields.any { it.maps.isNotEmpty() })
    }

    /**
     * The accumulate flag lives on the component, not on the field that carries
     * it: `record.cycles` is an 8-bit wrapping counter whose component unpacks
     * into the full-width `total_cycles`.
     */
    @Test
    fun anAccumulatingComponentIsFlaggedAsSuch() {
        val cycles = Factory.createField(Profile.MesgNum.RECORD, RecordMesg.CYCLES_FIELD_NUM)
        assertNotNull(cycles)
        assertEquals(1, cycles.components.size)
        assertTrue(cycles.components[0].accumulate)
        assertEquals(RecordMesg.TOTAL_CYCLES_FIELD_NUM, cycles.components[0].fieldNum)
    }

    /**
     * The profile version is packed as `major * 1000 + minor`, not `major * 100`.
     * The minor number runs past 100 — 21.205 here — so a factor of 100 would
     * fold it into the major version and make 21.205 indistinguishable from
     * 23.5, and disagree with what the encoder stamps into every file header.
     */
    @Test
    fun theProfileVersionIsScaledTheSameWayTheFileHeaderScalesIt() {
        assertEquals(Fit.PROFILE_VERSION, Profile.VERSION)
        assertEquals(
            (Profile.VERSION_MAJOR * Fit.PROFILE_VERSION_SCALE + Profile.VERSION_MINOR).toUShort(),
            Profile.VERSION,
        )
        assertEquals(Fit.PROFILE_MAJOR_VERSION, Profile.VERSION_MAJOR)
        assertEquals(Fit.PROFILE_MINOR_VERSION, Profile.VERSION_MINOR)

        // A profile whose minor number exceeds 99 is exactly the case a factor
        // of 100 would corrupt, and every FIT profile since 20.x is one.
        assertTrue(Profile.VERSION_MINOR > 99)
        assertEquals(Profile.VERSION_MAJOR, Profile.VERSION.toInt() / Fit.PROFILE_VERSION_SCALE)
    }

    @Test
    fun mesgNumConstantsMatchTheGeneratedClasses() {
        assertEquals(Profile.MesgNum.FILE_ID, FileIdMesg().globalMesgNum)
        assertEquals(Profile.MesgNum.RECORD, RecordMesg().globalMesgNum)
        assertEquals(Profile.MesgNum.SESSION, SessionMesg().globalMesgNum)
        assertEquals("Session", Factory.mesgName(Profile.MesgNum.SESSION))
    }
}
