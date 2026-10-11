# Foreground location

`core/location/domain` exposes access status, one device fix and an explicit
Settings operation. Its data module maps platform DTOs and technical exceptions
through `safeLocationCall`. No HTTP or HRIS policy belongs in these modules.

Android uses the OS location manager and an Activity-owned permission registration.
iOS uses Core Location. Both stop acquisition on cancellation; the platform host
closes the source. A cancelled permission request retains its callback slot until
the OS answers, so a late result cannot complete a newer request. Android keeps a
weak Activity reference and unregisters the launcher on destruction.

The consuming use case decides when to request permission, bounds acquisition,
checks freshness and scope, and decides whether an action may proceed without a
fix. `OpenLocationSettings` is invoked only for an explicit user action. Returning
from Settings never silently submits attendance; the next action reads OS access
again. Raw fixes and coordinates are excluded from diagnostic text.

Android and iOS request foreground access only. Desktop and browser hosts currently
report location as unavailable. Consumers may offer a documented alternative;
these hosts never synthesize a location. Physical iOS permission/GPS acceptance
requires a device test in addition to compilation.
