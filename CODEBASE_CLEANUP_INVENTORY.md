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
