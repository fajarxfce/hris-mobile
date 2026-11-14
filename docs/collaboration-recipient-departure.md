# Recipient departure ownership

An explicit Back request flushes the route-owned recipient editor before producing a navigation effect. This waits only for local draft/retained-original writes, not network or delivery confirmation. Ordinary typing and Send remain optimistic; a failed local flush preserves input and leaves the route available for retry. Repeated Back while one departure is pending cannot create a second navigation request.

Each Back effect carries an exact departure identity. The visible route must call confirmBack immediately before navigating; an already-delivered or buffered effect cannot navigate after stop, route release, denial or disposal. Those boundaries cancel the departure wait/effect. An ordinary stop does not cancel the independently owned editor write, so a draft may finish saving while the retained route is hidden. A denial cancels an earlier departure, but a new explicit Back can still leave a restricted page.

This is presentation/navigation ownership only. The ViewModel does not call navigation APIs, change permission policy or schedule transport. The serial editor and existing domain use cases retain local write/cancellation/readback responsibilities. Lifecycle cleanup retires queued effects and exact requests; a request/generation check rejects late flush completion before emission.

Six new common cases cover draft flush, retained Send plus independent later draft, failed storage flush, stopping during a held write, an already-delivered stale effect, and denial followed by a newly requested Back. The ten existing recipient-thread cases and actual application DI remain controls. These state/lifetime tests are not rendered navigation or first-frame latency acceptance.

The product route is still inactive. Its real navigation consumer must call confirmBack, and rendered/root tests must exercise that connection. Selection/copy context, long-press actions, the actual page, bounded navigation ownership, independent canonical/recipient drafts and global pending/draft inbox identity remain required. Outgoing recipient drafts still support plain text only. Finite setup after retained Send must remain separate from passive opening/typing/renewal. Phone 93 remains installed; Group OFF, V117–140 undeployed and all 54 PRD groups OPEN. Preserve blocked SQLite preparation, failed Apple evidence and protected stores. HRIS policy stays out of Starter.

Validation: 18,363 cases (2,231 fresh / 16,132 exact unchanged), preserving all 18,351 prior identities plus 12 executions. Focused 18 and full root/Android/browser/Apple-source gates passed.

[Exact validation](measurements/collaboration-recipient-departure-2026-11-14.json).
