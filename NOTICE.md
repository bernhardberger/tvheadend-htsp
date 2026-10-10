# Notices and attribution

TVHeadend HTSP for Kotlin/JVM
Copyright (C) 2026 Bernhard Berger

SPDX-License-Identifier: GPL-3.0-only

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, version 3. It is distributed WITHOUT ANY WARRANTY; see
[LICENSE](LICENSE).

## History and modifications

This HTSP protocol library began as a modified extract of
[Preclikos/tvhstream](https://github.com/Preclikos/tvhstream) (GPL-3.0,
Copyright (C) 2026 the tvhstream authors). The predecessor in turn acknowledges
ideas and code from [TVHClient](https://github.com/rsiebert/TVHClient). The
standalone repository begins with the HTSP protocol extraction baseline instead
of embedding the predecessor application's unrelated Git history.

Bernhard Berger has modified and extended the extracted code since August 2026.
In October 2026 the frame encoder, the wire-message class and parts of the
connection service were reimplemented with the replaced code in view. The
library remains a derivative work of tvhstream. The dated
[CHANGELOG](CHANGELOG.md) and the Git history record all later changes.

## Corresponding Source

The complete Corresponding Source for each release, including its build
scripts, is the repository <https://github.com/bernhardberger/tvheadend-htsp>
at the release tag `v<version>`.

## Independence and third-party components

This library is not affiliated with, endorsed by, or sponsored by the
[TVHeadend project](https://github.com/tvheadend/tvheadend). The TVHeadend name
describes compatibility only.

The library uses Kotlin and Kotlin coroutines. Their own copyright and license
terms continue to apply. Exact versions are recorded in the root build. The
repository's Gradle wrapper and the vendored `.opencode/skills` are Apache-2.0
under their own notices and are not part of the published artifacts.
