# Shared SDK runtime ownership

`SdkRuntimeRegistry` and `SdkSpaceRuntimeIdentity` now live in common data code. Their
ownership decisions are unchanged: reservations count before native construction, failed or
unfinished shutdown retains capacity, and exact Company/Space identity fences publication and
credential handover. Android continues using the same registry through its runtime repository.
This gives the Apple adapter one existing ownership mechanism to use instead of copying it.

`SdkRuntimeMonitor` owns only synchronous exclusion. JVM uses the existing reentrant monitor;
Apple uses `NSRecursiveLock`. Reentrancy matters because an inspection can reserve a foreground
slot while already inside the monitor. Browser transitions execute without suspension on the
owning event loop; this does not share a registry between workers. Every platform releases its
monitor after an exception. Callbacks under the monitor remain short and synchronous; SDK I/O
and asynchronous resource draining stay outside it.

The two existing Space/handover test files move byte-for-byte to commonTest, preserving all
18 Android case identities. Three additional cases cover nested failure cleanup, competing
reservations against the five-client capacity, and simultaneous inspection of one unfinished
construction. Existing Android thread-race tests remain unchanged. Validation checks exact case
identities across targets, followed by the full application and unchanged nine-case Matrix suite.

This changes source ownership and the platform lock implementation. It adds no SDK client,
network request, retry rule, feature flag or production Apple binding. The registry remains
HRIS-specific because it carries Company/Space identity and handover semantics; it is not a
starter-template component. Native Apple execution and product integration remain separately
tracked in the SDK8 candidate and communications acceptance work.
