# Môme DM — follow-ups after v1 implementation (2026-08-22)

Deferred minors and parked findings from the per-task and final code reviews of branch `feature/momedm-v1`. None blocks on-device testing; triage before v1.1.

## Parked (real, deferred with ruling)
- Final: Ruling (PARKED, spec-level): I6 single fleet-wide secret kept for v1, documented in README limitations; per-device secrets planned — cost if wrong: one extracted secret compromises fleet until regenerate+re-provision.
- Final: parked — BLEClient.reportConnectedOnce check-then-set non-atomic vs MTU watchdog (µs window) — Ruling: real but negligible; synchronize in a follow-up — cost: rare double onConnected → re-handshake.
- Final: parked — PolicyManager.openPlay prefs read outside runCatching (non-IOException DataStore failure would crash service) — Ruling: real, deferred; same shape as openAddAccount — cost: crash on corrupt-store edge.
- Final: parked — second onServicesDiscovered orphans first MTU watchdog (guarded by _connectedReported) — Ruling: deferred — cost: redundant CCCD enqueue.
- Final: parked — BLEServer.stopServer synchronous close exceptions escape past catch(BLEException) in ControllerService — Ruling: deferred (platform swallows RemoteException) — cost: restart abort on exotic failure.

## Deferred minors
- Task 1: minor (deferred): colors.xml now unreferenced (dead resource)
- Task 2: minor (deferred): SettingsScreen getIdentifier lookup crashes if pref key lacks string (dead code now); copied UI files lack KDoc/LOG_TAG (verbatim copy)
- Task 4: minor (deferred): notify/op queue watchdog double-advance (no op identity); recursion on sync failures; no onConnected watchdog if requestMtu never completes; server doesn't track CCCD subscription; BLEClient scanner!! before adapter checks (inherited); small files lack LOG_TAG (verbatim).
- Task 5: minor (deferred): >9999 chunks throws from send(); Hex.decode lenient; thin member-level KDoc; ControllerEndpoint.deviceId readable pre-auth (consumers gate on authenticated).
- Task 5: minor (deferred): FrameLayer parses frame twice.
- Task 7: minor (deferred): member KDoc thin (being added in fix); decode catches broad Exception.
- Task 8: minor (deferred): runCatching swallows CancellationException in kioskOn/Off; runBlocking in ManagedSetup.persistExtras (receiver; goAsync would be nicer); stubs lack LOG_TAG/KDoc (replaced later); res(r,"") cosmetic.
- Task 8: minor (deferred): openAddAccount kiosk check outside runCatching.
- Task 9: minor (deferred): gatt.rsp.value shared staging slot; stale BLEClient scan-timeout race (mitigated by stopScan change); KDoc on private members; double notify per rescan.
- Task 10-11: minor (deferred): permission button shows raw permission string; LocalLifecycleOwner deprecated import.
- Task 12-13: minor (deferred): BLEServer ctor opens GATT before service added (connect in window stalls to 5 s timeout); ProvisioningController init overwrites whole State; NanoHTTPD daemon=false leak if stop() skipped; HotspotManager.onStopped not propagated; QR MARGIN 1 (quiet zone) + WriterException uncaught; NetUtils test only for pick().
- Task 12-13: minor (deferred): tick() passes null deviceId to onDropped even when HELLO seen; pick() gateway fallback to prefix order.
- Task 13: minor (deferred): buildQr() launch job not captured (one-shot, low risk).
- Task 14-15: minor (deferred): unused strings device_title/provision_title; _appsFor not scoped to DeviceScreen lifetime.
- Task 14-15: minor (deferred): BLEServer.stopServer defers close 1 s → two GATT handles during restart; replayed snackbar on gate re-entry.
