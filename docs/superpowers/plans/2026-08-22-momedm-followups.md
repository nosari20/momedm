# Môme DM — follow-ups after v1 implementation (2026-08-22)

Deferred minors and parked findings from the per-task and final code reviews of branch `feature/momedm-v1`. None blocks on-device testing; triage before v1.1.

## Parked (real, deferred with ruling)
- Final: Ruling (CLOSED, 2026-08-24): I6 single fleet-wide secret is now the settled product decision, not a v1 compromise — one secret and one PIN for the whole family, because a parent tracking a code per child is worse than the risk it removes. Per-device secrets will not be built. Cost accepted and documented in README limitations and docs/architecture.md: one extracted secret compromises the fleet until regenerate + re-provision.
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

## Plan 1 — kiosk v2 (2026-08-22, branch feature/kiosk-v2)

### Parked / deferred
- Task 1-2: minor (deferred): unused catch binding in decodeApps; no test for childPrefs default / sanitized pin pairing.
- Task 3-4: minor (deferred): RESULT pinned note computed pre-filter (STATUS carries truth); spec §7 'note' on SET_PREFS sanitization not emitted (brief said plain 'prefs applied'); AppLocale LF vs CRLF.
- Task 3-4: minor (deferred): no PolicyManager-level tests for locked/paused gating.
- Task 5: minor (deferred): icon bitmaps converted per lazy item; pinError resolved as String in VM (Plan 2 i18n); dead VM surface (addAccount/openUsageAccess/restartLink) kept for Plan 2.
- Task 5: minor (deferred): resumeTick/pinDialogOpen exposed as public MutableStateFlow; RESUMED not re-checked after bounce delay; managed_no orphan string.
- Task 6-7: minor (deferred): setPin two DataStore writes (tiny half-pair window, parent-recoverable); picker seeds remembered without keys; prefsJob starts before BLE try; pushPrefs duplicates id generation; tests don't assert clear-PIN absence / updateStatus nickname.
- Task 8: minor (deferred): debug provisioning path does not call setAsDefaultHome (Home goes to stock launcher when child mode off on the rig).
- Final: parked — resume() inside collectLatest cancellable by unrelated DataStore writes (self-heals); DeviceRegistry.reload() outside mutex; lockout restore startup race; malformed PIN pair at rest until next SET_PREFS; tryPin during restored lockout silently swallowed — Ruling: deferred, all self-healing or tiny windows.

## Plan 2 — parent UI + theme + FR/EN (2026-08-23, branch feature/kiosk-v2-ui)

### Parked / deferred
- Task 1: minor (deferred): SystemBars double enableEdgeToEdge (intentional); Pronote helpers unused until later tasks.
- Task 2: minor (deferred): pair_ssid_value/password_value FR colon spacing (%1$s: vs %1$s :); fold into Task 4 when the pairing screen uses them.
- Task 3: minor (deferred): AppLocale comment says API 34 vs actual 33 threshold (cosmetic); LEGAL/LICENSES routes render blank (pre-existing).
- Task 4: minor (deferred): start-child-mode vs choose-apps both open picker when off (UX redundancy); online-green color duplicated in DevicesScreen vs OnlineIndicator; old device_/provision_/main_route_ strings dead (Task 6 removes).
- Task 5: minor (deferred): ManagedLinkState.lastError raw strings unlocalized (no UI consumer; localize if wired later).
- Final: parked — SettingsScreen template cruft on blank LEGAL/LICENSES routes; ControllerService BLE-error snackbar + device RESULT r.msg English-only (Plan-1 scope); APPEARANCE icon = CATEGORIES icon; two snackbars per command — Ruling: deferred, pre-existing/cosmetic.
