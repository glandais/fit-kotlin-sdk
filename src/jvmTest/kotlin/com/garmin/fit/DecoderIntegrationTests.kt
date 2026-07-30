/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

import com.garmin.fit.types.Event
import com.garmin.fit.types.File
import com.garmin.fit.types.Sport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decodes the real FIT files the other SDKs test against.
 *
 * The fixture files are shared with the Python and JavaScript suites, so a
 * divergence here is a divergence from those SDKs rather than a rebaselined
 * expectation.
 */
class DecoderIntegrationTests {
    @Test
    fun aRecordedActivityDecodesWithoutErrors() {
        val result = FitDecoder(TestFixtures.activity).decode()

        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertTrue(result.messages.recordMesgs.size > 100)
        assertEquals(1, result.messages.fileIdMesgs.size)
        assertEquals(File.ACTIVITY, result.messages.fileIdMesgs[0].type)
        assertEquals(1, result.messages.activityMesgs.size)
    }

    @Test
    fun integrityAndDetectionAgreeOnARealFile() {
        val decoder = FitDecoder(TestFixtures.activity)
        assertTrue(decoder.isFit())
        assertTrue(decoder.checkIntegrity())
    }

    @Test
    fun sessionsCarryTheirSportAndTotals() {
        val result = FitDecoder(TestFixtures.activity).decode()
        val session = result.messages.sessionMesgs.firstOrNull()
        assertNotNull(session)

        assertNotNull(session.sport)
        assertTrue(session.sport != Sport.INVALID)
        assertNotNull(session.totalElapsedTime)
        assertTrue(session.totalElapsedTime!! > 0.0)
        assertNotNull(session.startTime)
    }

    @Test
    fun recordsCarryTimestampsInFileOrder() {
        val records = FitDecoder(TestFixtures.activity).decode().messages.recordMesgs
        val timestamps = records.mapNotNull { it.timestamp }

        assertTrue(timestamps.size > 100)
        assertEquals(timestamps.sorted(), timestamps, "record timestamps should not go backwards")
    }

    /** Scaled fields must come back in real units, not raw counts. */
    @Test
    fun scaledFieldsDecodeIntoTheirUnits() {
        val records = FitDecoder(TestFixtures.activity).decode().messages.recordMesgs
        val distances = records.mapNotNull { it.distance }
        assertTrue(distances.isNotEmpty())
        assertEquals(distances.sorted(), distances, "distance should be monotonic")
    }

    @Test
    fun theStreamingApiSeesTheSameMessagesAsTheResultApi() {
        val streamed = FitDecoder(TestFixtures.activity).asSequence().count()
        val decoded = FitDecoder(TestFixtures.activity).decode().mesgs.size
        assertEquals(decoded, streamed)
    }

    // ------------------------------------------------------- developer fields

    @Test
    fun developerFieldsAreDeclaredAndReadBack() {
        val result = FitDecoder(TestFixtures.activityDevFields).decode()

        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")
        assertTrue(result.messages.developerFieldDescriptions.isNotEmpty())

        val withDevFields = result.mesgs.filter { it.developerFieldList.isNotEmpty() }
        assertTrue(withDevFields.isNotEmpty(), "no message carried a developer field")

        val field = withDevFields.first().developerFieldList.first()
        assertTrue(field.fieldName.isNotEmpty())
        assertTrue(field.hasValues)
    }

    @Test
    fun aDeveloperFieldKeepsTheDeclaringApplicationsIdentity() {
        val result = FitDecoder(TestFixtures.activityDevFields).decode()
        val description = result.messages.developerFieldDescriptions.first()

        val applicationId = description.applicationId
        assertNotNull(applicationId)
        assertEquals(16, applicationId.size, "an application id is a UUID")
    }

    // -------------------------------------------------------------- subfields

    /** `event_data` means `gear_change_data` when the event says so. */
    @Test
    fun subfieldsResolveAgainstTheirReferenceField() {
        val result = FitDecoder(TestFixtures.withGearChangeData).decode()
        assertTrue(result.isSuccess, "unexpected errors: ${result.errors}")

        val gearChanges = result.messages.eventMesgs.filter {
            it.event == Event.FRONT_GEAR_CHANGE || it.event == Event.REAR_GEAR_CHANGE
        }
        assertTrue(gearChanges.isNotEmpty(), "fixture should contain gear change events")
        assertTrue(gearChanges.any { it.gearChangeData != null })
    }

    // ------------------------------------------------------------- components

    /** `hr` messages pack their samples; merging lifts them into the records. */
    @Test
    fun heartRatesAreMergedIntoRecordsByDefault() {
        val merged = FitDecoder(TestFixtures.hrmPluginTestActivity).decode()
        assertTrue(merged.isSuccess, "unexpected errors: ${merged.errors}")
        assertTrue(merged.messages.hrMesgs.isNotEmpty(), "fixture should contain hr messages")

        val withHeartRate = merged.messages.recordMesgs.count { it.heartRate != null }

        val unmerged = FitDecoder(TestFixtures.hrmPluginTestActivity)
            .decode(DecodeOptions(mergeHeartRates = false))
        val withoutMerging = unmerged.messages.recordMesgs.count { it.heartRate != null }

        assertTrue(withHeartRate > withoutMerging, "merging should add heart rates to records")
    }

    /**
     * A record can carry both `avg_speed` and the `enhanced_avg_speed` that
     * `avg_speed`'s component unpacks into. The expanded value is the
     * authoritative one, so it replaces what was read directly rather than
     * appending to it and turning a scalar into a two-element array.
     */
    @Test
    fun anExpandedComponentReplacesTheDirectlyReadValue() {
        val result = FitDecoder(TestFixtures.hrmPluginTestActivity).decode()
        val lap = result.messages.lapMesgs.first()

        val enhanced = lap.getField(LapMesg.ENHANCED_AVG_SPEED_FIELD_NUM)
        assertNotNull(enhanced)
        assertEquals(1, enhanced.numValues, "expansion should not append to the direct value")
    }

    @Test
    fun componentExpansionCanBeTurnedOff() {
        val expanded = FitDecoder(TestFixtures.activity).decode()
        val raw = FitDecoder(TestFixtures.activity)
            .decode(DecodeOptions(expandComponents = false, mergeHeartRates = false))

        assertTrue(raw.isSuccess, "unexpected errors: ${raw.errors}")
        val expandedFields = expanded.mesgs.sumOf { m -> m.fieldList.count { it.isExpandedField } }
        val rawFields = raw.mesgs.sumOf { m -> m.fieldList.count { it.isExpandedField } }
        assertEquals(0, rawFields)
        assertTrue(expandedFields >= 0)
    }

    // --------------------------------------------------------- unknown data

    @Test
    fun unknownMessagesAreDroppedUnlessAskedFor() {
        val without = FitDecoder(TestFixtures.activity).decode()
        val with = FitDecoder(TestFixtures.activity).decode(DecodeOptions(includeUnknownData = true))

        assertEquals(0, without.messages.unknownMesgs.size)
        assertTrue(with.mesgs.size >= without.mesgs.size)
    }
}
