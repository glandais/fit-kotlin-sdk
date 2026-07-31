# FIT Kotlin Multiplatform SDK

Reads and writes Garmin FIT files. Profile 21.205.0-Release.

> **Everything under `src/` is machine-generated**, with one exception:
> `src/jsMain/resources`, the browser demo, is hand-written and local to this repository.

## Requirements

- **Gradle 9.x with JDK 25**, or Gradle 8.x with JDK 17 or 21. Gradle 8.14 does
  not run on JDK 25 at all, so on that combination set
  `JAVA_HOME=/path/to/jdk21`.
- Targets: `jvm`, `js` and `wasmJs` (both node and browser), and `wasmWasi`.
  The SDK itself is all in `commonMain` with no `expect`/`actual` and no
  `java.*` APIs, so adding a native target to `build.gradle.kts` needs no
  source changes. `jsMain` holds the JavaScript/TypeScript surface only — see
  [JavaScript and TypeScript](#javascript-and-typescript).

On the `js` target — not the Wasm ones, which have real types — `Byte`,
`Short`, `Int`, `Float` and `Double` are the same runtime type, a JS number.
Nothing in the API depends on telling them apart except [BaseType.of], which
infers the width of a field the profile does not know from the first value set
on it: there it answers `FLOAT64` for any number, holding the value rather than
truncating it. Set such a field through a `Field` you built yourself if you
need an exact width on that target.

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

## JavaScript and TypeScript

The `js` target carries one source set the other targets do not, `jsMain`, whose whole job
is to turn the Kotlin API into an idiomatic JavaScript one: plain objects instead of Kotlin
classes, `Date`s instead of FIT timestamps, `"cycling"` instead of `2`. It is published to
npm as [`@glandais/fit-kotlin-sdk`](https://www.npmjs.com/package/@glandais/fit-kotlin-sdk)
with TypeScript definitions, and the exported names are flat — the source set has no
`package`, precisely so that consumers write `decodeFit` and not `com.garmin.fit.decodeFit`.

```sh
npm install @glandais/fit-kotlin-sdk
```

```js
import { decodeFit, isFitFile, fitFieldInfo, fitProfileVersion } from '@glandais/fit-kotlin-sdk'

const bytes = new Int8Array(await file.arrayBuffer())
if (!isFitFile(bytes)) throw new Error('not a FIT file')

const result = decodeFit(bytes)                    // never throws; see result.errors
for (const record of result.messages.recordMesgs ?? []) {
    console.log(record.fields.timestamp, record.fields.heartRate, record.fields.positionLat)
}
```

`decodeFit` returns

```ts
{
    profileVersion: number | null,
    errors: { message: string, bytePosition: number }[],
    messages: Record<string, FitMessage[]>,   // { recordMesgs: [...], sessionMesgs: [...] }
    mesgs: FitMessage[],                      // the same objects, in file order
}

// FitMessage: { name, num, index, fields: Record<string, any>, developerFields: Record<string, any> }
```

which is the shape `@garmin/fitsdk` produces, so code written against the official
JavaScript SDK ports across with little more than a rename. Options, all optional:

```js
decodeFit(bytes, {
    mode: 'normal',            // or 'skipHeader', 'dataOnly'
    expandComponents: true,    // unpack bit-packed fields
    includeUnknownData: false, // keep messages absent from the profile
    mergeHeartRates: true,     // fold `hr` messages into `record`s
    applyTypes: true,          // enum values as names, date_time as Date, bool as boolean
})
```

`applyTypes` is the one knob with no Kotlin equivalent: the Kotlin API returns
`Sport.CYCLING` from a generated accessor, and JavaScript has no such accessors, so the
profile's names are resolved during the decode instead. Turn it off to see exactly what
the file stores.

Writing works the same way round — names or numbers, `Date`s or FIT seconds:

```js
import { encodeFit } from '@glandais/fit-kotlin-sdk'

const bytes = encodeFit([
    { name: 'fileId', fields: { type: 'activity', manufacturer: 'garmin', timeCreated: new Date() } },
    { name: 'record', fields: { timestamp: new Date(), heartRate: 140, cadence: 90 } },
])
```

`fitFieldInfo('record', 'heartRate')` reports what the profile says about a field — number,
units, scale, offset, base type, profile type — which is enough to label a table without a
lookup table of your own.

The package is a UMD bundle, which named imports above reach through any bundler
(webpack, Vite, esbuild) and through TypeScript. Node's own ESM loader is stricter and
needs the default import:

```js
import pkg from '@glandais/fit-kotlin-sdk'
const { decodeFit } = pkg
// or: const { decodeFit } = require('@glandais/fit-kotlin-sdk')
```

npm versions have three components where a release of this SDK may have four, so a revision
at an unchanged profile moves the fourth into the patch: Maven Central's `21.205.0.1` is
npm's `21.205.1`. `21.205.0` is the same on both.

One limit worth stating: a `uint64` beyond 2^53 arrives rounded, because a JavaScript
number has 53 bits of mantissa. Only the FIT `*_64` types and a few device serial fields
reach that far.

### Browser demo

`src/jsMain/resources` holds a decoder demo — drop a `.fit` file in, get a summary, the
track, the series, and every message in a table. Unlike `src/`, it is **not** generated,
so it is the one place in this repository that can be edited by hand.

```sh
gradle demo          # -> build/dist/js/productionExecutable
```

Serve that directory over HTTP (`python3 -m http.server`) — `file://` will not do, the
sample activity is fetched. It is published to GitHub Pages on every push to `develop` by
`.github/workflows/gh-pages.yml`:
<https://glandais.github.io/fit-kotlin-sdk/>.

## Build and test

```sh
gradle build
gradle jvmTest      # 198 tests on the JVM
gradle jsNodeTest   # the same tests plus the JS surface, on Node
gradle demo         # the browser demo, into build/dist/js/productionExecutable
```

## Reference

- Protocol and profile docs: https://developer.garmin.com/fit
- Cookbook recipes: https://developer.garmin.com/fit/cookbook/
