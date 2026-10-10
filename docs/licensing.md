# Licensing, attribution, and independence

The HTSP library is licensed under the GNU General Public License v3.0 only
(SPDX `GPL-3.0-only`). The license text controls; this summary does not replace
it. See the controlling [LICENSE](../LICENSE).

When conveying covered library work, keep intact applicable copyright, license,
and no-warranty notices, and give recipients a copy of GPLv3. Modified source
must mark the work as changed with the relevant date as required by GPLv3.
When conveying object code, provide Corresponding Source using a method
permitted by GPLv3. Preserve the applicable notices in [NOTICE.md](../NOTICE.md).
Every published jar carries `LICENSE` and `NOTICE.md`; the POM names the
release tag whose repository checkout is the complete Corresponding Source.

## Lineage

This library is an independently maintained descendant of
[Preclikos/tvhstream](https://github.com/Preclikos/tvhstream).
It began as a modified extract of predecessor work and is not wholly original.
The standalone repository begins with the HTSP protocol extraction baseline
instead of embedding the predecessor application's unrelated Git history.
Between tvhstream and this repository the code briefly lived in the
maintainer's private TVHeadend Player SDK monorepo; [`extraction/`](extraction/)
holds the frozen record of that extraction and is kept unchanged.

In October 2026 the frame encoder, the wire-message class and the connection
service's parameter and transport-ownership code were reimplemented. The work
was done with the replaced code in view, and other parts, such as the frame
decoder and the reader loop, keep the predecessor's structure. The library
therefore remains a derivative work of tvhstream under GPL-3.0-only.

## Independence

The library is developed and maintained independently of the TVHeadend project.
It is not affiliated with, endorsed by, or sponsored by the TVHeadend project.
Project identity: [TVHeadend project](https://github.com/tvheadend/tvheadend).
The TVHeadend name
describes compatibility only. These statements do not create a support,
publication, distribution, or release-readiness claim.
