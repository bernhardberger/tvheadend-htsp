# Contributing

`htsp-protocol` deliberately uses invalid-Java binary names for Kotlin-internal aliases and, where generated code owns them, `@JvmSynthetic` helpers; preserve their Kotlin and Java invisibility and the ABI checks that enforce it.
