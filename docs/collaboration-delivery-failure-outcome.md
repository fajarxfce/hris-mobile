# Delivery timing after a suspended failure

Actual Apple control 43443248470 at c2d9b5ea753083967adc40129187e9e5c6fcb413
executed 5,892 tests with one failure. The existing delivery timing test received null instead
of Failed after a suspended IllegalStateException. Portable 13,929 passed, but this control
is not fully accepted and its iOS host build was skipped. Failed reports and logs are retained.

The app-specific tracer now explicitly assigns Failed in catch(Throwable), then rethrows the
original failure before recording the final stage. Normal results, cancellation and timeout
keep their existing paths. This follows the existing measureOperation exception handling
(c50a8983); the observed native failure is evidence for this path, not proof of a general
compiler defect. No exception payload, message content or credentials enter diagnostics.

All nine existing timing tests remain. An additional shared test throws AssertionError after
suspension and a stage transition through both room and worker entry points. It checks exact
failure propagation, independent intervals, the final Failed outcome and payload exclusion.
The hosted portable job now explicitly selects all ten timing methods. Previously it selected
other application suites but omitted this class.

Focused 12 and full 16,316 pass locally, preserving all 16,314 preceding identities and adding
two JVM/browser executions. Android compilation/lint, browser execution, Apple source/test
compilation and the application proof pass. These results do not establish an Apple runtime fix.
A new combined source control must execute the failed original method, the new regression, all
previously executed identities and the iOS host. Expected 20,006 is a planned count, not a result.

There is no transport, native send, Group activation or phone change. Shared measureOperation
already contains this handling in Starter, so no additional foundation port is required.

Evidence: `docs/measurements/collaboration-delivery-failure-outcome-2026-11-13.json`.
