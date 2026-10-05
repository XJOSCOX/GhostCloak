# Phase R1 — codebase cleanup inventory

Inventory baseline: reviewed `main` at `9ca18d4591ecb9ab1b783963febcfb4d285a1c7d` (2026-10-04). This is an inventory, not permission to delete or change behavior. The worktree contains pre-existing Android Studio dependency and launcher-icon edits, untracked design assets and diagnostic logs; observations about those paths describe the worktree, not a clean reviewed build. No VPS, phone, emulator, or adversarial endpoint was accessed.

Method: inspected tracked source, Gradle and resource references, manifests, test task selection, current infrastructure templates, and documentation; ran strict, offline `:backend:compileKotlin :test-support:compileTestKotlin :app:compileDebugKotlin --rerun-tasks --warning-mode all`. The compile passed. Static reference searches cannot prove reflection, Android resource overlays, or deployed compatibility are absent. A candidate is not approved for removal until its callers, persisted-state compatibility and tests are checked in its proposed phase.

**Counts:** 34 actionable candidates: LOW 13, MEDIUM 17, HIGH 4. Five additional protected areas are listed under **DO NOT TOUCH WITHOUT REVIEW** and excluded from those counts. “Dead” below means no current production caller was found; it does not mean safe to delete. R2 = documentation, tests, resources and warning hygiene; R3 = behavior-preserving Android/backend simplification; R4 = security/protocol/deployment changes requiring design and compatibility review.

## CRITICAL — suspicious behavior / inspect before deletion

| ID | File / symbol | Evidence and callers/dependencies | Risk | Phase |
|---|---|---|---|---|
| C01 | `backend/src/main/kotlin/org/ghostcloak/backend/MailboxService.kt` `cleanup()` and `execute()` | `execute()` calls cleanup for requests; cleanup materializes all challenge and session rows before filtering, while mailbox deletion is bounded. PostgreSQL `Rows.all()` performs `SELECT *`. This is a table-growth/latency risk, not a proven security failure. Preserve challenge consumption and expiry semantics. | HIGH | R4 |
| C02 | `backend/src/main/kotlin/org/ghostcloak/backend/BlobService.kt` `cleanup()` | Called on blob-service construction and by `RetentionWorker`; `db.blobs.all().filter(...).take(128)` bounds deletions but not the initial full-table read. Candidate for a bounded SQL query with crash/orphan semantics retained. | HIGH | R4 |
| C03 | `backend/src/main/kotlin/org/ghostcloak/backend/PostgresDatabase.kt` `transaction()` | Every transaction takes the same PostgreSQL advisory transaction lock (`pg_advisory_xact_lock(734902182)`); production rate limiting, message operations and retention call through it. Potential serialization bottleneck; changing lock scope can affect atomic prekey/idempotency behavior. Benchmark and prove invariants first. | HIGH | R4 |
| C04 | `Android/app/src/main/java/org/ghostcloak/app/application/GhostViewModel.kt` `run()` | Each refresh computes `messagesForUi(...)` for every contact to populate previews, then again for the selected conversation. Calls depend on encrypted local storage and expiry filtering; evaluate cost with many contacts before altering read order or privacy gating. | HIGH | R3/R4 |

## HIGH VALUE CLEANUP

| ID | File / symbol | Evidence and callers/dependencies | Risk | Phase |
|---|---|---|---|---|
| H01 | `Android/app/src/main/java/org/ghostcloak/app/application/AppRuntime.kt` `use()` | One broad mutex scope opens storage, reconciles expiry, runs an arbitrary service block, refreshes attachment access, notifications, background eligibility and references. Many callers; split only with a lifecycle/transaction contract and process-death tests. | MEDIUM | R3 |
| H02 | `Android/app/src/main/java/org/ghostcloak/app/application/GhostViewModel.kt` `AppState` | Holds global contacts, previews, selected messages, unread maps, attachment cache, policies, fingerprint presentation and network status. All named fields checked have readers, so none is proven dead; consider narrowly scoped screen state after verifying recomposition and lock/sensitive-state behavior. | MEDIUM | R3 |
| H03 | `Android/app/src/main/java/org/ghostcloak/app/ui/navigation/GhostApp.kt` navigation graph | Route strings, settings return route, protected route set, selected-contact lifecycle and callbacks live in one composable. Callers are the screens and lock gate; route extraction could reduce duplication but must preserve process-recreation and settings authentication. | MEDIUM | R3 |
| H04 | `backend/src/main/kotlin/org/ghostcloak/backend/MailboxService.kt` `rateMaximum()` and `backend/src/main/kotlin/org/ghostcloak/backend/PostgresDatabase.kt` `PostgresRateLimiter` | Both development and production limiters share operation policy, but production deletes old rate-limit rows on each `allow()` while `RetentionWorker.cleanupRateLimits()` does the same cleanup. Confirm on-request expiry necessity before deduplicating. | MEDIUM | R3 |

## SAFE DELETE CANDIDATES

| ID | File / symbol | Evidence and callers/dependencies | Risk | Phase |
|---|---|---|---|---|
| D01 | `Android/app/src/test/java/org/ghostcloak/app/ExampleUnitTest.kt` template test | Tests only `2 + 2 == 4`; no app behavior or shared fixture uses it. | LOW | R2 |
| D02 | `Android/app/src/androidTest/java/org/ghostcloak/app/ExampleInstrumentedTest.kt` template test | Tests only the application package string. Confirm no CI smoke-test contract relies on the class before removal. | LOW | R2 |
| D03 | `backend/src/main/kotlin/org/ghostcloak/backend/OpaqueMailbox.kt` `OpaqueEnvelopeStorage` / `OpaqueMailbox` | No production backend caller found. `AcceptanceTest` uses `OpaqueMailbox` as a local opacity fixture and `backend/storage/README.md` documents it. Keep or move to test fixture only after replacing that architectural assertion. | MEDIUM | R3 |

## SAFE SIMPLIFICATION

| ID | File / symbol | Evidence and callers/dependencies | Risk | Phase |
|---|---|---|---|---|
| S01 | `Android/app/src/main/java/org/ghostcloak/app/ui/screens/ContactsScreen.kt` chat/directory dual mode | One composable branches repeatedly on `directory` for title, search, sort, click target, unread and row behavior; `GhostApp` calls it from `contacts` and `people`. Extract shared row/list logic only with both navigation paths tested. | MEDIUM | R3 |
| S02 | `Android/app/src/main/java/org/ghostcloak/app/ui/screens/ConversationScreen.kt` menu/callback defaults | Long callback parameter list with default no-op handlers; `GhostApp` supplies live callbacks. A typed action interface could make missing wiring visible, but screen tests use defaults and security actions require explicit migration. | MEDIUM | R3 |
| S03 | `backend/src/main/kotlin/org/ghostcloak/backend/PostgresDatabase.kt` `rows()` adapters | Generic `Rows` wrappers for accounts, devices and other tables centralize CRUD; table-specific prekey handling is separate. Review opportunities to reduce repeated query/encoding code without changing transaction or locking boundaries. | MEDIUM | R3 |

## WARNING CLEANUP

The forced current compile emitted the following warnings. Older examples of unnecessary `!!` in `BlobService.kt`/`LocalDeletionTest.kt` and a `NetworkAccount.kt` safe call **did not reproduce** with the current compiler; syntax is present, so revisit only if the compiler reports them again. The app compile is from this dirty worktree, not a reviewed release artifact.

| ID | File / symbol | Evidence and callers/dependencies | Risk | Phase |
|---|---|---|---|---|
| W01 | `Android/messaging/src/main/kotlin/org/ghostcloak/messaging/NotificationLedger.kt:8` private data-class constructor | Kotlin warns generated `copy()` visibility will change and become an error in language version 2.3. `NotificationLedger` is used for durable notification publication. **Future toolchain compatibility**; choose explicit copy visibility semantics. | MEDIUM | R2 |
| W02 | `Android/test-support/src/test/kotlin/org/ghostcloak/testing/PrekeyRefillTest.kt:100` `AccessTokenStore.save(value)` | Override parameter differs from interface `token`; current compile warns about named calls. Test-only fixture; safe rename after checking named-call sites. | LOW | R2 |
| W03 | `Android/app/src/debug/java/org/ghostcloak/app/PickerRegressionActivity.kt:50` deprecated override | Debug picker regression activity overrides deprecated API without annotation/suppression. Keep fixture while picker tests need it; warning is test scaffolding. | LOW | R2 |
| W04 | `Android/app/src/main/java/org/ghostcloak/app/ui/components/AppIcon.kt:15` `@DrawableRes` target | Kotlin warns annotation currently targets parameter only, with future default target change. Explicit use-site target preserves intended contract. | LOW | R2 |
| W05 | Android Gradle configuration `Configuration.setVisible(boolean)` | Gradle 9.8 warns this is removed in Gradle 11 during `:app` configuration; call site was not identified in project scripts, so inspect AGP/plugin provenance before changing toolchain. **Future toolchain compatibility**. | MEDIUM | R2/R4 |

## DEPENDENCY CLEANUP

| ID | File / symbol | Evidence and callers/dependencies | Risk | Phase |
|---|---|---|---|---|
| P01 | `Android/gradle/libs.versions.toml` and module build scripts | Alias search found every catalog library/plugin referenced by a build script. No catalog entry is proven unused. Review `implementation` versus `api` exposure and direct versus transitive uses with dependency insight before pruning. | MEDIUM | R3 |
| P02 | `Android/app/build.gradle.kts` QR libraries | Direct ZXing core and JourneyApps embedded dependencies coexist. QR generation uses core; capture activity uses embedded. Determine whether embedded already exports core and whether explicit core pin is needed before deduplicating; strict verification metadata must follow any approved change. | MEDIUM | R3 |
| P03 | `Android/gradle/verification-metadata.xml` | Verification metadata has accumulated entries for historical versions and source/javadoc artifacts during IDE sync. Do **not** delete by name alone: check resolved debug, release, androidTest and JVM graphs, then prune only artifacts absent from all supported configurations. Current local catalog/wrapper/metadata are user-modified. | MEDIUM | R3 |

## RESOURCE CLEANUP

| ID | File / symbol | Evidence and callers/dependencies | Risk | Phase |
|---|---|---|---|---|
| R01 | `Android/app/src/main/java/org/ghostcloak/app/ui/components/AppIcon.kt` `Glyph` entries | Static search of production Kotlin found no `Glyph.BLOCK`, `CALL`, `CAMERA`, `CHECK`, `DELETE`, `EDIT`, `FAVORITE`, `FINGERPRINT`, `HELP`, `HOME`, `LOGOUT`, `MICROPHONE`, `NOTIFICATIONS`, `PRIVACY`, `REPORT`, `SHARE`, or `VIDEO` callers outside the enum. Matching `gc_*.xml` are therefore resource candidates, not proven unused across previews/tests/reflection. | LOW | R2 |
| R02 | `Android/app/src/main/res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml` | Current worktree adaptive icon XML references `@mipmap/ic_launcher_foreground`; old drawable foreground/background appear unreferenced by main manifest/launcher XML. Check clean HEAD and Android resource merger before any deletion. | MEDIUM | R2 |
| R03 | `Android/app/src/main/res/mipmap-*`, `GhostCloak-Icons/`, `Android/app/src/main/ic_launcher-playstore.png` | Launcher densities and design-source assets are actively edited in the user workspace. Their apparent overlap is intentional design work until owner confirms canonical sources; do not overwrite. Inventory placement/build-size only. | LOW | R2 |

## TEST CLEANUP

| ID | File / symbol | Evidence and callers/dependencies | Risk | Phase |
|---|---|---|---|---|
| T01 | `Android/test-support/build.gradle.kts` test selection | Normal `test` excludes `PostgresTest*` and `StagingTest*`, with separate guarded `postgresTest` and `stagingTest`. Document matrix so green normal tests are not misread as database/staging coverage; preserve isolation. | LOW | R2 |
| T02 | `Android/test-support/src/test/kotlin/org/ghostcloak/testing/LiveAdversarialA1Test.kt` | A1 is in the normal test source set and skipped at runtime unless `GHOSTCLOAK_ADVERSARIAL_LIVE=true`. Move to a dedicated, explicit test task/source set in a later phase; preserve authorization and safety guards. No A1 test was run here. | MEDIUM | R3 |
| T03 | `Android/app/src/androidTest/java/org/ghostcloak/app/LocalOperation*ProbeTest.kt`, `RetainedProbeStorageTest.kt`, reboot tests | Specialized Safe Exit/process-death fixtures are opt-in/assumption guarded. Their coverage is security evidence; identify which run in normal connected suite and which need explicit orchestration before pruning overlap. | LOW | R2 |
| T04 | `backend/src/main/kotlin/org/ghostcloak/backend/LocalServer.kt`, `LocalEncryptedRouter.kt`, `DevelopmentRateLimiter` | Fixture implementations have numerous `test-support` callers and the debug demo uses the local router. They are not dead production dependencies simply because the live backend uses PostgreSQL. Consider packaging separation only after tests/demo contracts are mapped. | MEDIUM | R3 |

## DOCUMENTATION CLEANUP

| ID | File / symbol | Evidence and callers/dependencies | Risk | Phase |
|---|---|---|---|---|
| O01 | `backend/api/README.md` | Says only `OpaqueEnvelopeStorage` exists and production TLS/auth/quotas/retention are blocked. Current `ProductionServer`, `MailboxService`, nginx v2 allowlist and `RetentionWorker` implement them. Replace stale present-tense claims while retaining historical context elsewhere. | LOW | R2 |
| O02 | `backend/auth/README.md` | Says authentication is not implemented; current `MailboxService` has challenge/verify/recovery and device-auth binding. Current design docs are `Android/ANONYMOUS_IDENTITY_DESIGN.md` and infrastructure runbooks. | LOW | R2 |
| O03 | `backend/key-directory/README.md` and `backend/storage/README.md` | Say no network endpoint/future authenticated directory and only local in-memory mailbox. Current v2 lookup/allocation and PostgreSQL backend contradict present-tense text. Preserve threat-model caveats. | LOW | R2 |
| O04 | `Android/DEVELOPMENT.md` sections around lines 146, 152 and `Android/DESIGN.md:116` | Historical Phase 1I.2 text says media UI and attachment controls do not exist; current `AttachmentControls`, `AttachmentPresentation`, and conversation UI do. Mark those sections explicitly historical or point to current behavior. | LOW | R2 |
| O05 | `infrastructure/nginx.conf.template` versus `infrastructure/tunnel/nginx-origin.conf.template` and `attachments.nginx.conf.fragment` | Multiple ingress examples exist. Current tunnel template has v2 allowlist and `/health`; attachment fragment adds `/v1/attachments`; older generic template has broad `location /`. Label current deployment path unambiguously; do not apply older template to production. | MEDIUM | R2 |

## HISTORICAL KEEP — security and operational evidence

These are **not** cleanup candidates: `backend/src/main/resources/db/V001__foundation.sql` through `V008__prekey_allocation_retry.sql` (checksums and restore history); `SECURITY_AUDIT_CURRENT.md`; Q-series remediation and deployment records; `infrastructure/BACKUP_POLICY.md`, `DEPLOYMENT_PHASE_1C2_HISTORICAL.md`, `ABUSE_RESISTANCE_Q2.md`, `DEPLOYMENT.md`; `Android/BUILD_PROVENANCE.md`; adversarial plan/ledger and test evidence. Historical references to username/V001, old protocol and prior releases should remain clearly dated, not rewritten as if they describe current runtime behavior.

## DO NOT TOUCH WITHOUT REVIEW — protected investigation list

| Area | File / symbol | Evidence / callers and reason for protection | Risk | Phase |
|---|---|---|---|---|
| G01 | Username-era credential refusal | `Android/messaging/.../NetworkAccount.kt` reads `network/auth-private` and returns `legacy_auth_requires_reset`; `NetworkController` and `GhostViewModel` preserve the fail-closed path. V007 removed server username state. Removing this requires proof no old local installs remain. | HIGH | R4 |
| G02 | Protocol v1-looking identifiers | `GhostCloakContactQr` uses `ghostcloak://contact/v1/`; `BlobRoutes` uses `/v1/attachments`; `Disappearing.kt` contains padding/profile `v1` markers. These are active payload/endpoint format versions, distinct from retired username API v1. | HIGH | R4 |
| G03 | Trust/session migration state | `TrustStore` reads `trust/$id/1`, missing trust-state as UNVERIFIED, `trust-candidate` and previous state; `SignalProtocolEngine` uses those in identity-change and fingerprint flows. `SignalStore` pins keys. Stored-state migration/identity safety requires dedicated tests. | HIGH | R4 |
| G04 | Safe Exit and inactivity journals | `Android/app/.../access/DurableLocalOperationJournal.kt`, `LocalOperationCoordinator.kt`, `AndroidLocalDestruction.kt`, `SafeExitRecovery.kt`, `DurableInactivityStore.kt`; process-death and cross-boot fail-closed behavior depends on exact state transitions. Never collapse or clear states as generic UI cleanup. | HIGH | R4 |
| G05 | Attachment compatibility/clipboard protection | `AttachmentCapability.kt`, authenticated padding compatibility, `SensitiveClipboardProvider.kt` and `SensitiveTextSelection.kt` look like wrappers but enforce interoperability or sensitive copy behavior; the latter comment records a real Compose selection crash. Require device and security tests before removal. | HIGH | R4 |

## Recommended sequence and assurance gaps

1. **R2:** Correct current-versus-historical documentation, remove only template tests after CI check, verify resource-merger references for unused glyphs, fix directly reproduced low-risk compiler warnings, document explicit test matrix. Preserve all user icon/dependency work.
2. **R3:** Benchmark and simplify app state/UI and fixture packaging; review dependency graph and metadata in a clean, matching Android Studio build; isolate A1 as an explicit task. Behavioral invariants and existing identity/data must remain intact.
3. **R4:** Design-review database cleanup/locking and any protocol, crypto, trust, Safe Exit, legacy-auth or deployment change. Run focused database/process-death/compatibility tests before any security-sensitive edit. Do not infer deployed state from source.

No confirmed functional security defect was established by this inventory. C01–C04 are performance/availability risks needing measurement. The stale backend READMEs and broad older nginx template are an operational documentation hazard if followed as current instructions. `PostgresDatabase.expireAllocations()` already owns a transaction in this baseline; V008 is untouched. The forced compile emitted exactly W01–W05; no current `TODO`, `FIXME`, or `HACK` markers were found in production Kotlin/SQL/config searches. No normal JVM tests or connected tests were run in R1.

## Phase R2 disposition (2026-10-04)

The R1 rationale above is retained. **COMPLETED** means R2 changed the item and ran the relevant source/build check; it is not a claim that all later architectural work is finished. **SKIPPED** means evidence was insufficient or the item overlaps user work. Medium/high items were not cleaned up.

| R1 IDs | R2 status | Reason / R2 action |
|---|---|---|
| C01–C04 | DEFERRED TO R4 | Full-table reads, shared lock and repeated message loading need measurement and behavioral review. |
| H01–H03 | DEFERRED TO R3 | Runtime/state/navigation architecture is outside low-risk cleanup. |
| H04 | DEFERRED TO R3 | Rate-limit expiry duplication may affect availability/semantics. |
| D01–D02 | COMPLETED | Removed generated arithmetic/package-name template tests; neither covered app behavior. |
| D03 | DEFERRED TO R3 | `OpaqueMailbox` still has an acceptance-test caller. |
| S01–S03 | DEFERRED TO R3 | UI/backend adapter simplification needs behavior tests. |
| W01 | DEFERRED TO R3 | Generated `copy()` visibility of a public nested type needs an API decision; no warning suppression. |
| W02 | COMPLETED | Renamed only the test override parameter to `token` and its local variable to `currentToken`; null assertion and token transitions are unchanged. |
| W03 | COMPLETED | Debug-only picker fixture intentionally overrides deprecated `startActivityForResult`; narrowly suppresses the override diagnostic at that method with an explanatory comment. Callback behavior unchanged. |
| W04 | COMPLETED | Made `@DrawableRes` target explicitly `@param`, preserving the existing constructor-parameter contract. |
| W05 | DEFERRED TO R4 | Gradle's `setVisible` deprecation originates outside identified project script call sites; toolchain/plugin review required. |
| P01–P03 | DEFERRED TO R3 | No dependency or verification-metadata entry was proven unused; user has uncommitted catalog/metadata/wrapper edits. |
| R01 | SKIPPED | Glyphs have no direct production Kotlin references, but resource/design ownership and merger behavior were not proven sufficiently for deletion. |
| R02–R03 | SKIPPED | Launcher and adaptive-icon resources overlap current uncommitted user design work; no assets were changed. |
| T01 | SKIPPED | Test-task selection is already explicit in Gradle; a fuller test matrix belongs with R3 harness isolation. |
| T02 | DEFERRED TO R3 | A1 harness remains untouched; no live adversarial opt-in was used. |
| T03 | SKIPPED | Safe Exit/reboot probes are security regressions, not obsolete tests. |
| T04 | DEFERRED TO R3 | Local fixtures still have test and debug-demo callers. |
| O01–O03 | COMPLETED | Backend API/auth/directory/storage READMEs now distinguish current source from historical fixture descriptions and link to the current runbook. |
| O04 | COMPLETED | Old attachment statements in `Android/DEVELOPMENT.md` and `Android/DESIGN.md` are marked as the historical Phase 1I.2 baseline. |
| O05 | DEFERRED TO R3 | Ingress templates have distinct deployment purposes; no nginx behavior or files changed. |
| G01–G05 | DEFERRED TO R4 | Protected security/compatibility areas remain untouched. |

R2 also ignored only root and `Android/` transient `*.log` files after `git ls-files '*.log'` found no tracked logs. Existing untracked logs were not deleted. No dependency versions, migrations, protocol behavior, local user assets, or backend query/transaction paths were changed.

### R2 documentation classification

| Classification | Paths | Basis |
|---|---|---|
| CURRENT | `backend/api/README.md`, `backend/auth/README.md`, `backend/key-directory/README.md`, `backend/storage/README.md`, `infrastructure/README.md`, `infrastructure/DEPLOYMENT.md` | These now describe or link to current v2/V007–V008 source and the reviewed operator runbook; live deployment is not inferred from source. |
| HISTORICAL | `infrastructure/DEPLOYMENT_PHASE_1C2_HISTORICAL.md`, `protocol/API_V1.md`, dated Phase 1I.2 sections in `Android/DEVELOPMENT.md` and `Android/DESIGN.md`, V001–V008 migrations and Q-series evidence | Retain as dated design, migration, validation and rollback history. |
| SUPERSEDED for current deployment | Broad `infrastructure/nginx.conf.template` and older Phase 1C2 instructions | Current source runbook points to `infrastructure/tunnel/nginx-origin.conf.template` plus the separate attachment fragment. Neither older file was removed or edited; compare deployed ingress before any later cleanup. |

### R2 validation and lint observations

Strict offline JVM tests passed: app debug 114, app release 107, test-support 170 (one opt-in A1 test skipped), attachments 4; zero failures. `:backend:installDist`, debug and release Android assemblies succeeded. The focused forced compiler rerun emitted only W01 plus Gradle's W05 deprecation; W02–W04 did not recur. This is validation of the dirty development worktree, not a clean reviewed build.

`lintDebug` reported **8 errors and 40 warnings** and failed. Six `MissingClass` errors refer to WorkManager alarm-service removal entries in `Android/app/src/main/AndroidManifest.xml`; the WorkManager catalog is user-modified, so R2 did not edit the manifest or dependency. One `LifecycleCurrentStateInComposition` error is at `LockScreens.kt:139`, and one `PermissionImpliesUnsupportedChromeOsHardware` error is at manifest line 7. These are additional findings, not proven R2-introduced regressions. The 40 warnings include user-edited dependency-version suggestions, current icon resources, a Compose modifier-order suggestion, optional KTX conversions, exported-provider review and other manifest/resource checks. They require separate triage; no lint baseline or blanket suppression was added. No Android app was installed.

Remaining original LOW-risk candidates: R01, R03, T01 and T03 (four). All were skipped for reference/ownership or security-test uncertainty. The new lint findings should be assessed before a formal reviewed-build gate; they do not authorize changes to lock behavior, WorkManager, app distribution or user icon assets in R2.

## Phase R2.5 — Android lint and build-warning triage (2026-10-04)

Baseline: app lintDebug from the pre-R2.5 Android Studio worktree reported **8 errors and 40 warnings**. The catalog, wrapper, verification metadata and launcher/icon work below were pre-existing user edits and were not changed. Each baseline finding is recorded verbatim below; file and line identify the source at triage time. SAFE FIX means the R2.5 patch addresses that finding. DEFER TO R3/R4 means no source change in this phase.

| Severity | Rule ID | File:line | Exact lint message | Disposition |
|---|---|---|---|---|
| Error | MissingClass | Android/app/src/main/AndroidManifest.xml:34 | Class referenced in the manifest, androidx.work.impl.background.systemalarm.SystemAlarmService, was not found in the project or the libraries | SAFE FIX |
| Error | MissingClass | Android/app/src/main/AndroidManifest.xml:35 | Class referenced in the manifest, androidx.work.impl.background.systemalarm.ConstraintProxy$BatteryChargingProxy, was not found in the project or the libraries | SAFE FIX |
| Error | MissingClass | Android/app/src/main/AndroidManifest.xml:36 | Class referenced in the manifest, androidx.work.impl.background.systemalarm.ConstraintProxy$BatteryNotLowProxy, was not found in the project or the libraries | SAFE FIX |
| Error | MissingClass | Android/app/src/main/AndroidManifest.xml:37 | Class referenced in the manifest, androidx.work.impl.background.systemalarm.ConstraintProxy$StorageNotLowProxy, was not found in the project or the libraries | SAFE FIX |
| Error | MissingClass | Android/app/src/main/AndroidManifest.xml:38 | Class referenced in the manifest, androidx.work.impl.background.systemalarm.ConstraintProxy$NetworkStateProxy, was not found in the project or the libraries | SAFE FIX |
| Error | MissingClass | Android/app/src/main/AndroidManifest.xml:39 | Class referenced in the manifest, androidx.work.impl.background.systemalarm.ConstraintProxyUpdateReceiver, was not found in the project or the libraries | SAFE FIX |
| Error | LifecycleCurrentStateInComposition from androidx.lifecycle | Android/app/src/main/java/org/ghostcloak/app/access/LockScreens.kt:139 | Lifecycle.currentState should not be called within composition | SAFE FIX |
| Error | PermissionImpliesUnsupportedChromeOsHardware | Android/app/src/main/AndroidManifest.xml:7 | Permission exists without corresponding hardware <uses-feature android:name="android.hardware.camera" android:required="false" /> tag | SAFE FIX |
| Warning | RedundantLabel | Android/app/src/main/AndroidManifest.xml:44 | Redundant label can be removed | SAFE FIX |
| Warning | DiscouragedApi | Android/app/src/main/AndroidManifest.xml:22 | Fixed screen orientations will be ignored in most cases, starting from Android 16. Android is moving toward a model where apps are expected to adapt to various orientations, display sizes, and aspect ratios. | SAFE FIX |
| Warning | ExportedContentProvider | Android/app/src/main/AndroidManifest.xml:28 | Exported content providers can provide access to potentially sensitive data | SAFE FIX |
| Warning | ObsoleteSdkInt | Android/app/src/main/res/mipmap-anydpi-v26 | This folder configuration (v26) is unnecessary; minSdkVersion is 30. Merge all the resources in this folder into mipmap-anydpi. | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:3 | A newer version of androidx.work:work-runtime than 2.11.2 is available: 2.12.0 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:3 | A newer version of androidx.work:work-testing than 2.11.2 is available: 2.12.0 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:5 | A newer version of net.zetetic:sqlcipher-android than 4.19.0 is available: 4.19.1 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:6 | A newer version of androidx.room:room-compiler than 2.8.4 is available: 2.8.5 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:6 | A newer version of androidx.room:room-runtime than 2.8.4 is available: 2.8.5 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:7 | A newer version of org.jetbrains.kotlinx:kotlinx-coroutines-core than 1.10.2 is available: 1.11.0 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:8 | A newer version of org.jetbrains.kotlinx:kotlinx-serialization-cbor than 1.9.0 is available: 1.11.0 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:11 | A newer version of androidx.core:core-ktx than 1.10.1 is available: 1.19.1 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:13 | A newer version of androidx.test.ext:junit than 1.1.5 is available: 1.3.0 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:15 | A newer version of androidx.lifecycle:lifecycle-runtime-ktx than 2.6.1 is available: 2.11.0 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:16 | A newer version of androidx.activity:activity-compose than 1.8.2 is available: 1.13.0 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:17 | A newer version of androidx.fragment:fragment than 1.8.9 is available: 1.9.1 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:18 | A newer version of org.jetbrains.kotlin.jvm than 2.2.10 is available: 2.4.20 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:18 | A newer version of org.jetbrains.kotlin.plugin.compose than 2.2.10 is available: 2.4.20 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:18 | A newer version of org.jetbrains.kotlin.plugin.serialization than 2.2.10 is available: 2.4.20 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:19 | A newer version of androidx.compose:compose-bom than 2026.02.01 is available: 2026.09.00 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:20 | A newer version of androidx.navigation:navigation-compose than 2.9.7 is available: 2.10.2 | DEFER TO R3 |
| Warning | GradleDependency | Android/gradle/libs.versions.toml:21 | A newer version of androidx.lifecycle:lifecycle-viewmodel-compose than 2.9.4 is available: 2.11.0 | DEFER TO R3 |
| Warning | ModifierParameter from androidx.compose.ui | Android/app/src/main/java/org/ghostcloak/app/ui/components/AppIcon.kt:32 | Modifier parameter should be the first optional parameter | DEFER TO R3 |
| Warning | UnusedResources | Android/app/src/main/res/values/colors.xml:3 | The resource R.color.purple_200 appears to be unused | DEFER TO R3 |
| Warning | UnusedResources | Android/app/src/main/res/values/colors.xml:4 | The resource R.color.purple_500 appears to be unused | DEFER TO R3 |
| Warning | UnusedResources | Android/app/src/main/res/values/colors.xml:5 | The resource R.color.purple_700 appears to be unused | DEFER TO R3 |
| Warning | UnusedResources | Android/app/src/main/res/values/colors.xml:6 | The resource R.color.teal_200 appears to be unused | DEFER TO R3 |
| Warning | UnusedResources | Android/app/src/main/res/values/colors.xml:7 | The resource R.color.teal_700 appears to be unused | DEFER TO R3 |
| Warning | UnusedResources | Android/app/src/main/res/values/colors.xml:8 | The resource R.color.black appears to be unused | DEFER TO R3 |
| Warning | UnusedResources | Android/app/src/main/res/values/colors.xml:9 | The resource R.color.white appears to be unused | DEFER TO R3 |
| Warning | UnusedResources | Android/app/src/main/res/drawable/ic_launcher_background.xml:2 | The resource R.drawable.ic_launcher_background appears to be unused | DEFER TO R3 |
| Warning | UnusedResources | Android/app/src/main/res/drawable/ic_launcher_foreground.xml:1 | The resource R.drawable.ic_launcher_foreground appears to be unused | DEFER TO R3 |
| Warning | UseKtx | Android/app/src/main/java/org/ghostcloak/app/access/AndroidLocalDestruction.kt:71 | Use the KTX extension function String.toUri instead? | DEFER TO R4 |
| Warning | UseKtx | Android/app/src/main/java/org/ghostcloak/app/access/AndroidLocalDestruction.kt:77 | Use the KTX extension function SharedPreferences.edit instead? | DEFER TO R4 |
| Warning | UseKtx | Android/app/src/main/java/org/ghostcloak/app/ui/theme/Appearance.kt:19 | Use the KTX extension function SharedPreferences.edit instead? | DEFER TO R3 |
| Warning | UseKtx | Android/app/src/main/java/org/ghostcloak/app/attachments/AttachmentPresentation.kt:302 | Use the KTX extension function String.toUri instead? | DEFER TO R3 |
| Warning | UseKtx | Android/app/src/main/java/org/ghostcloak/app/ui/qr/ContactQrScreen.kt:40 | Use the KTX function createBitmap instead? | DEFER TO R3 |
| Warning | UseKtx | Android/app/src/main/java/org/ghostcloak/app/ui/components/NotificationSettings.kt:41 | Use the KTX extension function SharedPreferences.edit instead? | DEFER TO R3 |
| Warning | UseKtx | Android/app/src/main/java/org/ghostcloak/app/attachments/PhotoPreparation.kt:173 | Use the KTX function createBitmap instead? | DEFER TO R3 |
| Warning | UseTomlInstead | Android/app/build.gradle.kts:123 | Use version catalog instead | DEFER TO R3 |

WorkManager 2.11.2's runtime AAR has no SystemAlarmService or ConstraintProxy components named by the six stale removal nodes. Both baseline merged variants already omit them. The active startup initializer removal, foreground-service removal and diagnostics receiver removal remain; the AndroidX Startup provider stays non-exported and the JobScheduler/RescheduleReceiver entries are untouched. The QR scanner is optional and the merged variants already declare camera hardware optional through the scanner dependency, so the app manifest now states this directly. The merged provider already had android:exported=false; stating it in the source closes a misleading warning without opening a component. The redundant launcher label and default unspecified orientation were removed with identical inherited/default behavior. The lifecycle-state read could miss Compose recomposition, so the biometric readiness check observes lifecycle state and has a focused lifecycle transition UI test. No direct side effect was performed in composition.

Additional compiler/build findings (outside Android lint):

| Severity | Rule / source | File:line or symbol | Exact message | Disposition |
|---|---|---|---|---|
| Warning | Kotlin data-class copy visibility | Android/messaging/src/main/kotlin/org/ghostcloak/messaging/NotificationLedger.kt:8:22 | Non-public primary constructor is exposed via the generated 'copy()' method of the 'data' class. The generated 'copy()' will change its visibility in future releases. | SAFE FIX |
| Warning | Gradle deprecation | com.android.internal.application / Configuration.setVisible(boolean) | The Configuration.setVisible(boolean) method has been deprecated. This is scheduled to be removed in Gradle 11. | DEFER TO R4 |
| Warning | JDK native/Unsafe libraries | Tink shaded protobuf UnsafeUtil / libsignal runtime | sun.misc.Unsafe::arrayBaseOffset has been called by com.google.crypto.tink.shaded.protobuf.UnsafeUtil$MemoryAccessor. | DEFER TO R3 |

The NotificationLedger copy-visibility compiler warning is handled by @ConsistentCopyVisibility; its internal constructor and copy API stay internal. The Gradle Configuration.setVisible(boolean) deprecation originates from plugin com.android.internal.application in the Gradle problems report, not repository build logic; changing the user-modified AGP version or plugin internals is deferred to R4 toolchain review. It is scheduled for Gradle 11 removal, not an immediate Gradle 9.8 failure. JVM library warnings about native access/Unsafe also originate in third-party Tink/libsignal code and are deferred to dependency review. The 18 GradleDependency warnings overlap the user's catalog experiments; the icon resource and mipmap-anydpi-v26 warnings overlap user asset work. No such file was edited.

Final strict, offline debug and release lint: **0 errors, 37 warnings each**. Debug and release APK assemblies passed. Debug Android test APK compiled and was installed only on disposable emulator-5554; the focused biometric lifecycle and Contact QR suite passed (11 tests). The QR tests cover QR payload decoding, the scan-result contract and camera-denied manual fallback; they do not establish optical camera decoding through the emulator's virtual camera. Final merged debug and release manifests retain a non-exported AndroidX Startup provider, JobScheduler service and RescheduleReceiver; WorkManager auto-initializer, foreground service and diagnostics receiver remain removed. The camera remains optional. No genuine exposed-component, backup, cleartext, or authentication bypass defect was found in this triage. The two Safe Exit UseKtx suggestions are deferred to R4 because changing synchronous deletion calls merits dedicated failure/recovery review. No medium/high-risk architecture refactor was performed.

The strict, offline JVM matrix passed: app debug 114, app release 107, test-support 170 (one opt-in A1 test skipped), and attachments 4: **395 passed, 0 failed, 1 skipped**. Backend build was not rerun because no shared or backend build logic changed. All emulator ADB commands explicitly targeted emulator-5554; no physical phone or VPS was accessed.

## Phase R3 — measured simplification and test isolation (2026-10-05)

The worktree began at reviewed main `328b5d9f55e2565d79ed05a2857ffbac8d49d67d` with pre-existing launcher, icon, version catalog, verification metadata, wrapper, Android Studio and dependency-document edits. None were staged or changed by R3. This phase did not alter crypto, protocol, SQL, backend API, deployment configuration or saved-state semantics.

### Refresh read audit and measurement

`GhostViewModel.run()` is used by manual refresh, foreground sync/polling and actions. After the action it loads contacts, optionally marks the selected conversation read, loads unread counts, policies and unread expiries, then called `ConversationService.messagesForUi()` for every visible contact's preview **and again** for the selected conversation. Each `messagesForUi()` call independently checks the contact/request presentation policy, calls `LocalRepository.messages()` (which performs expiry and sorted record reads), and decorates attachment/view-once content. The extra selected call therefore repeated repository work. `AppRuntime.use()` also refreshes attachment UI access and background/notification eligibility under its mutex; that work and the semantically distinct unread/expiry passes were not combined.

`ConversationRefreshSnapshotTest` uses three accepted contacts, one selected contact, and three synthetic messages. It compares the exact previous preview/selected-message expression with the replacement using a counting `EndpointRecords` fixture. It verifies identical preview keys, selected message ordering and content. Results for that refresh slice:

| Measure | Before | After | Reduction |
|---|---:|---:|---:|
| `messagesForUi()` calls | 4 | 3 | 25% |
| Message-key scans (`app/message/…`) | 8 | 6 | 25% |
| Message-record reads | 10 | 6 | 40% |

The selected conversation's UI list is now reused from its preview read, with no persistent cache or new invalidation rule. An absent/non-contact selection still yields an empty message list and does not load that ID. If selection changes while service reads suspend, the old snapshot publishes no selected messages; the new selection's own refresh supplies its list. The regression test covers that mismatch. One selected list allocation/read is avoided; allocation bytes and elapsed time were not benchmarked because this small deterministic JVM fixture would make wall-time numbers misleading. The table is scoped to the preview/selected-message slice, not the entire `AppRuntime.use()` refresh. Unread counts, unread expiry and attachment access still perform their own reads for different purposes.

### R3 candidate disposition

| Candidate | Status | Evidence / boundary |
|---|---|---|
| C04, repeated message loading | COMPLETED (scoped) | Removed the selected contact's second `messagesForUi()` read per refresh; counter-backed regression test confirms output and reductions. No cross-refresh cache. |
| H01, `AppRuntime.use()` scope | DEFERRED | It couples transaction/lifecycle, expiry, attachment access and notification state. Splitting it needs process-death and security-boundary design review. |
| H02, `AppState` size | KEEP | All named fields have production readers; none was proved dead or safe to move to screen-local state without lock/navigation review. No field removed. |
| H03, navigation graph | DEFERRED | Route extraction could alter protected navigation and process recreation; no exact duplicate with independent tests was established. |
| H04, rate-limit cleanup duplication | DEFERRED | On-request expiry and retention-worker cleanup differ in timing and transaction context. Backend behavior is out of R3 scope. |
| D03, `OpaqueMailbox` | KEEP | The acceptance test asserts an opaque, bounded, no-decryption relay between ciphertext transport and recipient decrypt. The production mailbox has auth, persistence and network behavior, so substituting it would change what this local security fixture proves. |
| S01, ContactsScreen dual mode | DEFERRED | Chat/directory click and unread behavior differ; no isolated identical helper with two-path coverage was established. |
| S02, ConversationScreen callbacks | DEFERRED | Security-action defaults and UI tests depend on current wiring; a typed action migration needs route and authorization regression tests. |
| S03, PostgreSQL row adapters | DEFERRED | Database/query architecture is explicitly outside this phase. |
| R01–R03, icon/launcher resources | BLOCKED BY USER WORK | Launcher and design-source work is uncommitted; static unused results do not establish design ownership. No resource removed. |
| Remaining 37 lint warnings | KEEP / DEFERRED | Version suggestions overlap user catalog edits; icon/obsolete resources overlap user assets; modifier/KTX/style suggestions require no product change now. `UseTomlInstead` overlaps user dependency edits. Safe Exit KTX and Gradle toolchain warnings remain R4 review. |
| Test-support suite separation | COMPLETED | Normal `test` explicitly excludes PostgreSQL, staging and A1 live classes. New `a1LiveTest` is opt-in and retains the test's own environment guard. Android connected tests remain a separate emulator-only workflow. |
| T02, A1 test isolation | COMPLETED | The ordinary test XML contains no `LiveAdversarialA1Test`; the explicit task cannot start without its environment confirmation, and the test retains its own guard. No A1 test was executed. |
| T03, destructive/reboot Android tests | DEFERRED | They remain in the separate Android instrumentation source set, with stage-argument guards on reboot/live probes. App test-task configuration overlaps user-edited build logic; no connected suite was run on a physical device. Dedicated emulator-only orchestration needs later review. |
| T04, backend local fixtures | KEEP | `LocalServer`, `LocalEncryptedRouter` and `DevelopmentRateLimiter` retain test/debug-demo callers and were not repackaged. |

During emulator validation, `MessengerDesignTest.requestRequiresAcceptanceButNotVerificationToReply` failed reproducibly because it searched for the old exact accessibility description `Security`. Production `ConversationScreen` already names the unverified control `Unverified contact · Security`; the test now asserts that precise existing label. This was a test expectation correction only, with no UI or verification-state change.

Final validation on the unchanged user dependency checkout used strict, offline dependency verification. App debug and release lint each reported **0 errors, 37 warnings**; both APK assemblies and the Android test APK passed. JVM tests: app debug 116, app release 109, test-support 169, attachments 4 = **398 passed, 0 failed, 0 skipped**. The ordinary test run produced no live A1 result file. On disposable `emulator-5554`, the focused MessengerDesign, UnreadMessages, RequestPrivacyScreen and ViewOnceScreen suite passed **11/11** after the stale accessibility assertion was corrected. All ADB commands explicitly targeted the emulator. No VPS, physical phone, live adversarial endpoint or production data was accessed.

No near-exact duplicate helper was consolidated: the apparently similar mark-read, unread-count and unread-expiry paths have different request/expiry side effects, and the two delivery-status passes have different terminal-state filters. Further merging would exceed the measured selected-read fix. No candidate was newly escalated to HIGH. R4 should review the broad runtime mutex, background/expiry query architecture, protected navigation and the remaining Gradle toolchain issue with dedicated security and process-death tests before any change.

## Phase R4 — bounded backend reads and concurrency review (2026-10-05)

The only implementation change in this phase is a set of explicit `BackendDatabase` queries. The in-memory implementation retains equivalent default filters; PostgreSQL overrides use parameterized SQL. No migration, protocol, crypto, retention deadline, rate policy, or lock was changed. Production remains protected by the same global advisory transaction lock.

| Existing read | Classification / bound and frequency | R4 disposition |
|---|---|---|
| Account by Ghost Cloak ID (lookup, allocation, registration collision) | B/C: up to 1,000 accounts; request path. | COMPLETED: unique indexed `accounts.ghostcloak_id` lookup; at most one row returned. |
| Device by routing ID (send, registration conflict) | B/C: at most one device per account, up to 1,000; request path. | COMPLETED: unique indexed `devices.routing_id` lookup. Auth-key recovery intentionally retains its full, bounded scan to preserve uniform existence handling. |
| Recipient mailbox (fetch, send capacity) | B: global cap 2,048, per-recipient cap 128; request path. | COMPLETED: recipient-indexed read for fetch; aggregate count/bytes for send. Global count remains an SQL `count(*)` to enforce the same cap. |
| Sender submissions (send capacity) | B: global cap 10,000, per-sender cap 1,024; request path. | COMPLETED: indexed sender count. Global count remains SQL `count(*)`. |
| Submission by server message ID (ACK) | B/D: global cap 10,000, ACK batch bounded by protocol. | COMPLETED for transfer volume: predicate SQL returns only matches. The existing schema has no `server_message_id` index, so PostgreSQL may still scan 10,000 rows; adding an index needs a migration and is DEFERRED. |
| Expired challenges/sessions (`cleanup()` on each request and retention) | B: challenges capped at 1,000; sessions at most one per device, at most 1,000. | COMPLETED: indexed expiry predicates select only IDs/hashes to delete. Expiry and challenge-consumption order remain unchanged. |
| Expired blobs (retention) | B: global cap 10,000, cleanup deletes at most 128 per pass. | COMPLETED: expiry-indexed, 128-row query; deletion and orphan reconciliation remain unchanged. Construction-time uploading recovery remains a bounded full scan, KEEP. |
| Allocation count/expiry | A: 10,000 global, 64/requester, 4/target caps; indexed SQL overrides already existed. | KEEP. No V008 semantics or cleanup limit changed. |
| Blob reservation capacity, owner upload check | B/D: up to 10,000 global; request path. | DEFERRED. Replacing global/owner byte accounting needs direct PostgreSQL fixture measurements and file/DB failure-boundary tests. |
| Prekey `all()` and auth-key recovery scan | A/C: bounded by 1,000 devices and at most 32 active bundles each. | KEEP. Recovery deliberately scans without an existence-dependent early return. |

Before/after query-shape evidence: each account/device lookup remains one SQL query but changes from transferring up to 1,000 rows to one indexed row. Recipient send usage changes from one global mailbox-row transfer (up to 2,048 ciphertext rows) to one aggregate over the recipient index (at most 128 rows examined under the cap); fetch still transfers recipient ciphertext, because it must deliver it. Blob expiry changes from transferring up to 10,000 metadata rows to at most 128. Challenge/session expiry changes from transferring all rows to only expired primary keys. The existing 2,048/10,000 global `count(*)` checks are unchanged. These are query/transfer bounds, **not measured throughput claims**. PostgreSQL `EXPLAIN` and concurrent throughput comparisons remain pending an isolated database.

**Global lock: KEEP.** `PostgresDatabase.transaction()` reuses its thread-local connection for nested calls, otherwise obtains an 8-permit connection budget, 5-second statement/lock limits, and one transaction-scoped advisory lock. This serializes independent work but protects check-then-write registration, mailbox capacity, challenge consumption, rate limits, allocation and idempotency. Existing PostgreSQL tests exercise concurrent challenge consumption, prekey allocation and duplicate submissions; R4 adds an explicit two-transaction serialization test. No lock narrowing is justified without measured contention and stronger race coverage, including independent account/device operations and rate-limit cap races. The new queries do not bypass transactions.

**Transaction boundaries: KEEP.** `MailboxService.execute()` commits challenge consumption separately before a verification attempt, then runs the action in its transaction. `cleanup()` owns a transaction; `expireMailbox()` assumes that caller transaction. `expireAllocations()` and `cleanupRateLimits()` own transactions and safely reuse a nested transaction. Blob reservation/upload/cleanup have transaction-scoped metadata changes around filesystem work; `budget()` nests in reservation and must remain atomic. No new direct-call transaction bug was found. RetentionWorker runs these owners sequentially and sets `healthy=true` after a successful later cycle. `/health` combines DB and worker readiness; it intentionally exposes only generic availability. An internal fixed-category reason would aid operators but needs a logging/operations design to avoid sensitive metadata; DEFERRED.

**Rate limits: KEEP.** `PostgresRateLimiter.allow()` deletes previous windows per request before checking the 2,048-row global cap. Retention also deletes old windows, but moving expiry solely to the 30-second worker would cause valid new principals to be rejected at a full cap until the next cycle. The operation/principal primary key supports the current equality lookup; per-request cleanup scans old windows because V001 has no `window_start` index. Changing that needs a migration and abuse/capacity measurements, so it is DEFERRED. The same transaction still performs expiry, count, and increment.

**Android runtime/navigation: DEFERRED or KEEP.** `ConversationService.action()` serializes engine/repository state; `AppRuntime.use()` serializes store lifetime, expiry, network and notification reconciliation; the ViewModel foreground mutex prevents overlapping foreground cycles. They have distinct scopes and are not proven redundant. Selected conversation, settings routing and protected-route checks include lock/process-recreation effects. R3 removed the demonstrably duplicate selected-message read; no additional state or navigation rewrite passed the required equivalence threshold here. The Safe Exit journal, legacy credential refusal, v1-format identifiers, trust migration and encrypted-state compatibility remain KEEP.

**Toolchain/lint: KEEP.** Gradle 9.8.0 `--warning-mode all` reports `Configuration.setVisible(boolean)` while configuring `:app`; no project script calls that method, so the warning appears plugin-originated and cannot be repaired safely without a plugin/toolchain update. Configuration-cache use succeeded in this phase. The 37 lint warnings are 18 dependency-version suggestions in the user-edited catalog, 9 unused-resource findings including user launcher/icon work, 7 optional KTX suggestions (two in synchronous Safe Exit destruction), 1 modifier-parameter style suggestion with positional callers, 1 user launcher `v26` suggestion, and 1 version-catalog suggestion overlapping build edits. None warrants an R4 source change. No dependencies or plugins were upgraded.

**Fixtures and remaining work:** The normal JVM task still excludes PostgreSQL, staging and live A1; no security coverage was removed. R4's targeted-query and lock tests live in the isolated PostgreSQL suite. The suite exposed two old fixture mismatches: a LOOKUP test assumed a one-request maximum despite the existing 3× policy, and the TLS fault injector watched `/v1/messages` instead of the current `/v2/messages`. Both test fixtures now match existing production behavior; no protocol or rate-limit implementation changed. Blob capacity aggregates, a possible server-message-ID index, advisory-lock narrowing, runtime mutex splitting, protected-navigation extraction and internal health categorization remain MEDIUM/HIGH ideas intentionally deferred; each needs a separate measured design and security review.

Validation: strict, offline JVM tests passed (attachments 4, test-support 169, app debug 116, app release 109 = **398**); the isolated PostgreSQL 16 suite passed **23/23**, including targeted-query, shared-lock, challenge/prekey/submission race, retention and gateway retry cases. Android `lintDebug` passed with **0 errors, 37 warnings**; debug/release assemblies and backend `installDist` passed. A local disposable PostgreSQL container was used; no VPS, physical phone, live A1 endpoint or adversarial test was accessed. The codebase cleanup campaign can be considered **complete for the verified R1–R4 scope**: all remaining medium/high candidates are explicitly KEEP, DEFERRED or NOT WORTH COMPLEXITY, not silently claimed as optimized.

## Phase R5 — final sweep and R-series closure (2026-10-05)

R1 inventoried 34 actionable candidates and five protected security/compatibility areas. R2 removed the template tests and corrected current documentation and compiler hygiene; R2.5 resolved lint errors; R3 removed a measured duplicate selected-conversation read and isolated live A1 tests; R4 replaced broad PostgreSQL reads with targeted queries while retaining the global transaction lock. R5 rechecked tracked files, Kotlin/SQL markers, callers, resources, documentation and build warnings. No new source or resource was proved safe to delete. The only R5 correction is the current root README's stale claim that the owner-reported V007 backup restore had not been rehearsed; historical audit text remains unchanged.

### Final R1 candidate disposition

| Final disposition | IDs | Count | Evidence / boundary |
|---|---|---:|---|
| COMPLETED | C01, C02, C04, D01, D02, W01–W04, T01, T02, O01–O05 | 16 | Bounded queries, measured selected-read removal, template deletion, warning fixes, test-task documentation/isolation and current-document corrections are implemented. O05 is documentation of the current ingress template, not removal of the older example. |
| KEEP | H02, H04, D03, S03, P01, P02, T03, T04 | 8 | App state has live readers; on-request rate-limit expiry prevents cap stalls; opacity and local fixtures have security/debug callers; PostgreSQL row adapters remain useful; QR libraries and catalog aliases have direct users; guarded Android security probes remain valuable. No dependency is proven redundant. |
| DEFERRED — PERFORMANCE | C03 | 1 | The global advisory lock serializes transactions but protects check-then-write invariants; narrow it only after contention measurements and race coverage. |
| DEFERRED — SECURITY-SENSITIVE | H01, H03, S02 | 3 | Runtime mutex, protected navigation and security-action callback restructuring need process-death, authorization and lock-boundary review. |
| DEFERRED — TOOLCHAIN | W05 | 1 | Gradle 9.8 reports external Android plugin `Configuration.setVisible(boolean)` use; no project script call was found. Resolve through a separately reviewed plugin/toolchain change before Gradle 11. |
| BLOCKED BY USER WORK | P03, R01–R03 | 4 | Verification metadata, catalog, launcher and icon/design assets overlap intentional uncommitted Android Studio work; no entry or resource was removed. |
| NOT WORTH COMPLEXITY | S01 | 1 | Chat and directory modes have distinct click, unread and routing behavior; splitting the composable without a proven duplication would add coordination without an established benefit. |

**Totals:** 16 completed, 8 kept, 5 deferred (1 performance, 3 security-sensitive, 1 toolchain), 4 blocked by user work, 1 not worth complexity = **34/34 explicitly classified**. Protected G01–G05 remain **DEFERRED — SECURITY-SENSITIVE** outside the 34: legacy credential refusal, active v1-format identifiers, trust migration, Safe Exit/inactivity journals and attachment/clipboard compatibility were not modified or claimed cleaned.

### Post-R4 backend and toolchain check

The new account/device, recipient mailbox, sender-count, expiry and ACK queries bind caller values as parameters and preserve the service-level request/transaction boundary. Existing unique account-ID/device-routing indexes, recipient/sender and expiry indexes support their corresponding predicates. ACK's `server_message_id` equality predicate reduces rows returned but has no dedicated index; the global 10,000-row cap bounds the scan. Blob expiry selects at most 128 eligible rows in stable expiry/ID order; construction-time uploading reconciliation remains a separate bounded scan. The in-memory implementation retains matching predicates. PostgreSQL regression coverage checks row/recipient isolation, expiry, nested transaction reuse and concurrency. No row visibility, retention or allocation semantics were changed in R5.

Still intentional performance/scaling debt: advisory-lock narrowing requires concurrent correctness and throughput measurements; blob-capacity global/owner accounting needs SQL-shape measurement and file/DB failure tests; an ACK lookup index needs a reviewed migration; rate-limit window cleanup needs a cap/abuse design and likely an index because worker-only expiry can stall new principals; broader connection-budget and transaction architecture needs load evidence. These are not dead-code blockers and were not implemented in R5.

Android lint remains **0 errors, 37 warnings** in both debug and release: 18 `GradleDependency` version suggestions in the user-edited catalog (blocked by user work; later dependency review), nine `UnusedResources` findings (seven generic colors and two launcher drawables, with icon ownership unresolved; later resource review), seven optional `UseKtx` suggestions (two in synchronous Safe Exit destruction; later security review for those two, otherwise optional style), one `ModifierParameter` style suggestion (positional callers; not worth an R5 API edit), one `ObsoleteSdkInt` for user launcher resources (blocked by user work), and one `UseTomlInstead` in the user-edited app build script (blocked by user work). The Gradle 11 `setVisible` deprecation is plugin-originated and deferred to toolchain review. Java's native-access warning comes from third-party libsignal; no project code calls the restricted method. No current project Kotlin compiler or JVM-target warning was found. Gradle configuration-cache storage succeeded; its reuse can be invalidated by the Git-derived build provenance input when the checkout changes, which is expected rather than a cache correctness failure. No version was upgraded or lint suppression added.

Current docs now align on protocol v2 for account/auth/directory/mailbox, the 12-character public Ghost Cloak ID with encrypted display names, the V007 destructive identity cutover and additive V008 allocation table, the separate `/v1/attachments` route, current origin/tunnel deployment template, and Android Studio debug versus clean reviewed-build provenance. The final Q register remains complete on owner-reported operational evidence; adversarial A1 is partial coverage (17 pass, one blocked), A2+ not run. Old v1/phase status remains in labeled historical records. Source review does not certify a newly installed Android APK or fresh VPS state.

R5 removed **no** source files, resources, migrations, fixtures or generated artifacts. Tracked-file scan found no accidental APK, database, log or screenshot artifact; the Gradle wrapper JAR is intentional. No current Kotlin/SQL TODO, FIXME or HACK marker was found. The remaining candidates are explicit design, toolchain or user-owned work, not known dead code. No protocol, schema, cryptographic or security behavior changed.

Final validation on the existing dirty development checkout: strict, offline attachments 4, test-support 169, app debug 116 and app release 109 JVM tests passed (**398/398**); isolated PostgreSQL 16 passed **23/23**; disposable `emulator-5554` passed **11/11** focused MessengerDesign, UnreadMessages, RequestPrivacyScreen and ViewOnceScreen tests. Debug and release lint passed with **0 errors, 37 warnings each**; debug/release builds, Android test APK build and backend `installDist` passed with strict dependency verification. The uniquely named PostgreSQL container was removed after testing. The focused emulator tests do not replace clean reviewed-build signoff or hardware-specific security validation. No physical phone, VPS, live adversarial endpoint or production data was accessed. No cleanup-introduced regression was found. The R cleanup program is **COMPLETE** for its reviewed scope; future performance, security-sensitive and toolchain work requires its own scope and review.
