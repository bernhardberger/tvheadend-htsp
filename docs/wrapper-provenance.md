# Gradle wrapper provenance

## Version-neutral launcher and wrapper JAR

The launcher scripts and wrapper JAR were copied byte-for-byte from committed
source `68e20fba3b21579ea9eea6271a4dae0d6b0b12a8`, never from its worktree:

| Path | SHA-256 |
|---|---|
| `gradlew` | `3238afb2aed5cb16eb7d6718077e7138059108f007b54179e9cc157c5a6e0e89` |
| `gradlew.bat` | `94102713eb8fb22d032397924c0f38ab2da783ba60d07054339f1190a0c4e2cd` |
| `gradle/wrapper/gradle-wrapper.jar` | `76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3` |

These bytes do not prove or identify the downloaded Gradle distribution.

## Official distribution pin

`gradle-wrapper.properties` separately pins the official URL
`https://services.gradle.org/distributions/gradle-9.0.0-bin.zip`, enables URL
validation, and records distribution SHA-256
`8fad3d78296ca518113f3d29016617c7f9367dc005f932bd9d93bf45ba46072b`.
Exact-SHA CI verifies these authorities before invoking the wrapper. Local
static review did not execute or download Gradle 9.
