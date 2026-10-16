# Company data synchronization

Account opens **Data and sync** for the selected company. Each row shows the last
completed acquisition of its local read model: personal profile, employees, Inbox,
attendance/calendar, overtime, leave, document catalog, expenses and payslips.

A timestamp comes from a completed, published change feed. Incomplete replacement
and delta pages retain the previous completion time. The UI never advances it from
a refresh response or its own clock. If a later step, such as history acquisition,
fails, the error remains visible alongside the feed data already saved. No
timestamp means the full feed has not completed; individually saved details or
drafts may still exist. Neither age nor an unavailable connection removes owned
local records.

## Ownership

`ObserveCompanyData` and `SyncCompanyData` are shared application ports in the
workspace domain. Each feature binds its existing observation and acquisition
use cases through its own DI module. Home depends on those ports, not feature
repositories or another feature's presentation layer.

Observation uses the feature's normal account/company ownership and shared read
admission. Repositories decrypt only the published checkpoint metadata; the raw
replica store does not read resource payloads for this query. A confirmed denial
also closes the status row, including while cleanup or persistence fails. A
complete admitted acquisition can reopen only its captured denial revision.

## Explicit refresh

The page requests one dataset or all registered datasets, with at most three
acquisitions running at once. Each request carries the original workspace ID and
uses the same feature execution guard as the worker. A partial run reports that
synchronization is incomplete; the next explicit request resumes stored progress.

The route-owned MVI ViewModel drops duplicate requests, cancels acquisition when
the page stops, and releases observations when its owner is cleared. Returning to
the page reads metadata without starting HTTP work. An explicit refresh also
reopens a completed storage subscription through the same read-admission policy.
Each dataset keeps one active storage observation. A retry waits for cancelled
storage work to finish; cancelling an intermediate retry cannot start an
overlapping reader. Late values cannot update the cleared or cancelled owner.

Progress refers only to work requested by this page. Stored checkpoints and
scheduler enqueue responses do not prove that another worker is running.

## Separate workflows

Refresh updates acquired facts. It does not submit unfinished forms or retry
business commands. Drafts, command outcomes and file transfers remain visible in
their owning workflows; this page does not count them or infer delivery from a
completed read-model sync. A shared pending-action summary remains separate work.
