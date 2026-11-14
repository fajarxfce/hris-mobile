# Measured local contact snapshots

A desktop contract measures 5,000 synthetic contacts in the actual SQLite ReplicaStore with
AES-GCM encryption. It seeds through the existing 50-contact acquisition pages, checks every
returned contact, searches the retained snapshot, reads one exact contact, then closes/reopens
the database. No HTTP client, Matrix runtime or live account participates.

The first measured run read the directory in 185 ms initially, 126 ms through a new unchanged
observation, and 97 ms after reopening SQLite. Every full read used 25 bounded storage pages,
5,000 documents and 5,001 decryptions. One selected contact used one page/document and two
decryptions in 4 ms. Five searches on the retained snapshot took 15 ms total. These are diagnostic
samples from this Linux host, not latency thresholds, statistical benchmarks or phone timings.
The root check also executes the test; its separate raw measurements remain in the evidence.

Use one account/Group-owned directory snapshot for contact presentation and local search.
Opening another subscription rebuilds the whole snapshot even when persisted data is unchanged;
retaining the owned consumer avoids that work on tab navigation. New publications still replace
the snapshot. Search belongs off the UI thread with obsolete work cancelled. An additional name
index is not justified by this host measurement alone; measure actual Android rendering later.
Account replacement and confirmed denial must clear the old presentation and cancel observation.
HR company selection must not own or authorize the Group snapshot. Keep policy in domain use
cases and pure selectors, repositories over data sources, and UI state in the retained ViewModel.

The existing raw capacity is 10,000 contacts, acquisition pages contain 50, and local storage
pages contain 200. Public contacts expose at most eight company labels plus hasMoreCompanies.
A complete company filter cannot treat omitted membership as absent; resolve that contract before
introducing such a filter. Do not request private employee profiles or fetch on each local query.

This change adds the diagnostic storage test and records the presentation decision. It does not
activate Group routing, alter product storage or ship an APK. Continue Group contact/root
integration with the existing swipe tabs, compact search/filter placement and scroll-aware FAB.

Evidence: `docs/measurements/collaboration-contact-snapshot-2026-11-14.json`.
