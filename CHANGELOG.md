# Changelog

## [Unreleased]

Clarify that the client requests HTSP v43 by default while the typed surface
has a v44 coverage ceiling. Remove the protocol evidence and generation tooling
(`derive.py`, `report.py`, `htsp_spec.json`, `HTSP_METHOD_MATRIX.md`,
`htsp_surface.py`, the four typed Kotlin generators, and the generated-source
drift checker); the existing `Generated*.kt` sources are now maintained by
hand. This changes no API, ABI, or runtime behavior.

## [0.1.1]

This release records the initial provisional baseline under `0.1.1` after the
`v0.1.0` release attempt stopped before publication. It does not promise source,
binary, or behavioral compatibility or support.

The initial provisional baseline contains the standalone Kotlin/JVM HTSP v44
protocol library and its typed outcome API. Publication and availability are
independently verified external state and are not established by this entry.

## [0.1.0]

The signed `v0.1.0` tag did not produce a release. Its workflow stopped before
Central or GitHub publication, and the tag is not reused.
