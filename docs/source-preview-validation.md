# Source preview validation

15 November 2026.

Windows/JDK 17 editor-only source build passed. The main test suite passed, including language fallback, routing, annotations, sessions and panel contracts. All 707 used interface keys are present in English. Native C toolchain packaging and live-device tests were not rerun.

The Windows source-only build uses --without-c-toolchain; full compiler provisioning is separate. Test changes fix JavaFX module discovery when using an installed JDK rather than a bundled runtime.

No physical controller was written to. Historical release reports are retained as historical evidence, not new acceptance. This publication does not supply a signed installer.
