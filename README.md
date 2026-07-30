# FIT Kotlin Multiplatform SDK

Reads and writes Garmin FIT files. Profile 21.205.0Release.

> **Everything under `src/` is machine-generated.** The next profile drop
> replaces the whole tree, so hand edits are lost. Change the FitGen templates
> under `fitgen/templates/kt/` instead.

## Requirements

- **Gradle 9.x with JDK 25**, or Gradle 8.x with JDK 17 or 21. Gradle 8.14 does
  not run on JDK 25 at all, so on that combination set
  `JAVA_HOME=/path/to/jdk21`.
- Only the `jvm` target is declared, but all the code is in `commonMain` with no
  `expect`/`actual` and no `java.*` APIs, so adding `js()` or a native target to
  `build.gradle.kts` needs no source changes.

## Decoding

`decode()` never throws. A truncated or corrupt file still yields everything
that decoded before the problem, alongside a description of it:

```kotlin
val result = FitDecoder(bytes).decode()

result.errors.forEach { println("${it.message} at byte ${it.bytePosition}") }

result.messages.recordMesgs.forEach { record ->
    println("${record.timestamp}  ${record.heartRate} bpm  ${record.distance} m")
}
result.messages.sessionMesgs.forEach { session ->
    println("${session.sport}  ${session.totalElapsedTime} s")
}
```

Values are typed by the profile: scaled fields arrive in their real units as
`Double`, enums as generated enum classes, timestamps as
`kotlin.time.Instant`. Everything nullable, because FIT has no null and an
absent field is indistinguishable from one holding the invalid sentinel. Use
`Field.getRawValue` when that difference matters.

### Streaming

For files too large to hold decoded. These do throw `FitFormatException`,
having no result object to carry an error in:

```kotlin
FitDecoder(bytes).asSequence()
    .filterIsInstance<RecordMesg>()
    .forEach { println(it.heartRate) }

FitDecoder(bytes).read { mesg -> println(mesg) }
```

Both, and `decode()`, are the same pull-based cursor underneath.

The SDK has no dependencies, so it ships no `Flow` entry point. Decoding is
synchronous and CPU-bound, so one is a one-liner over the sequence:

```kotlin
fun FitDecoder.asFlow(options: DecodeOptions = DecodeOptions()): Flow<Mesg> =
    asSequence(options).asFlow().flowOn(Dispatchers.Default)
```

### Options

```kotlin
FitDecoder(bytes).decode(
    DecodeOptions(
        mode = DecodeMode.NORMAL,     // or SKIP_HEADER, DATA_ONLY
        expandComponents = true,      // unpack bit-packed fields
        includeUnknownData = false,   // keep messages absent from the profile
        mergeHeartRates = true,       // fold `hr` messages into `record`s
    ),
)
```

### Untyped access

The typed classes are a view over numbered fields; nothing is hidden behind
them:

```kotlin
val mesg: Mesg = result.mesgs.first()
mesg.getFieldValue(RecordMesg.HEART_RATE_FIELD_NUM)
mesg.fieldList.forEach { println("${it.fieldName} = ${it.toList()} ${it.units}") }
```

## Encoding

A FIT file must open with a `file_id` message. `close()` is not optional: it
back-patches the header's data size and appends the file CRC. `encodeFit {}`
calls it for you.

```kotlin
val bytes = encodeFit {
    write(
        FileIdMesg().apply {
            type = File.ACTIVITY
            manufacturer = Manufacturer.GARMIN
            timeCreated = Clock.System.now()
        },
    )
    write(RecordMesg().apply { heartRate = 140u; distance = 1234.5 })
}
```

Message definitions are emitted only when the layout changes, so a run of
same-shaped records costs one definition rather than one each.

## Extending a file

Never redefine an existing profile message or field: that breaks every other
reader. FIT's sanctioned extension mechanism is developer fields, declared in
the file itself by a `developer_data_id` / `field_description` pair. The
decoder surfaces them on `Mesg.developerFieldList`, and the declarations it met
on `FitMessages.developerFieldDescriptions`.

To write them, declare each field with `FitEncoder.registerDeveloperField`
before the first message carrying it — the encoder emits the declaring message
pair for you. `DeveloperFieldDescription.createField()` then makes a field to
`setDeveloperField` on a message; re-registering the descriptions a decode
returned re-encodes the same extension fields.

## Build and test

```sh
gradle build
gradle jvmTest
```

## Reference

- Protocol and profile docs: https://developer.garmin.com/fit
- Cookbook recipes: https://developer.garmin.com/fit/cookbook/
