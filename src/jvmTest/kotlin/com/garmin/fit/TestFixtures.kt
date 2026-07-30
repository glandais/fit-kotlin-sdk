/////////////////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Garmin International, Inc.
// Licensed under the Flexible and Interoperable Data Transfer (FIT) Protocol License; you
// may not use this file except in compliance with the Flexible and Interoperable Data
// Transfer (FIT) Protocol License.
/////////////////////////////////////////////////////////////////////////////////////////////

package com.garmin.fit

/**
 * Loads the `.fit` files under `src/jvmTest/resources`.
 *
 * These live in jvmTest rather than commonTest because there is no portable way
 * to read a resource from commonMain, and inlining a 90 KB activity as a
 * `byteArrayOf` literal would blow past the JVM's 64 KB limit on the enclosing
 * method. Nothing in `src/commonMain` depends on any of this: the SDK itself
 * stays free of platform APIs.
 */
internal object TestFixtures {
    fun load(name: String): ByteArray {
        val stream = TestFixtures::class.java.classLoader.getResourceAsStream(name)
            ?: error("test fixture $name is missing from src/jvmTest/resources")
        return stream.use { it.readBytes() }
    }

    val activity: ByteArray by lazy { load("Activity.fit") }
    val activityDevFields: ByteArray by lazy { load("ActivityDevFields.fit") }
    val withGearChangeData: ByteArray by lazy { load("WithGearChangeData.fit") }
    val hrmPluginTestActivity: ByteArray by lazy { load("HrmPluginTestActivity.fit") }
}
