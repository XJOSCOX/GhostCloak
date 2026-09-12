# Messenger design

Phase 1F's proposed private background delivery architecture and threat model are documented in [BACKGROUND_SYNC_DESIGN.md](BACKGROUND_SYNC_DESIGN.md), including the optional app-lock roadmap: initial UI lock and a later Keystore-gated maximum-security mode. Phase 1F.1 implements scheduled background FETCH/STORE/ACK; Phase 1F.2 adds an encrypted acceptance ledger and optional generic local notifications. Phase 1G.1 implements the optional root UI/app-access lock described below. Foreground polling cadence is unchanged. Notification taps enter the normal app root, pass the lock gate, then open Chats.

The theme follows the local GoXEV reference: Space Grotesk typography and neutral charcoal surfaces, now with a soft blue accent. Dark primary is `#8FB5FF`, with background `#1A1A1A` and surface `#222222`. Light mode uses `#315FAD` blue for text/button contrast and cool neutral surfaces. Avatars use the semantic brand container, without unrelated color palettes. Chat, Contact, Profiles and Settings have separate bottom-navigation destinations. Settings offers persistent Light, Dark and Automatic choices; Automatic is the default and follows Android's system appearance. System-bar icons also follow the selected appearance. The app uses local vector icons and initials; reference photos, artwork and unavailable feature controls are not copied into the product.

Layout references: [Inbox Chat App](https://dribbble.com/shots/24369393-Inbox-Chat-App) for inbox hierarchy and [Material canonical layouts](https://m3.material.io/foundations/layout/canonical-examples/overview) for list/detail organization.

The bottom navigation uses a compact rounded surface with a horizontal icon-and-label pill for the active destination; inactive destinations retain accessible icon-only tabs. Colors, typography, spacing and shapes use shared theme roles, including light and dark mode. The bar respects the system navigation inset and keeps 48 dp minimum tap targets.

## Screens

- Chats: With no unread messages, a compact “Chats” title and search/compose icons on the right. When unread messages exist, Search moves left, the centered count replaces “Chats”, and Compose stays right. Only unread incoming messages contribute to the count. Search toggles a local conversation-name field; closing it clears the query. The header has no brand icon or second title. Existing avatars stay in conversation rows. An 8 dp primary-color dot beside the preview identifies each conversation with unread incoming messages, with an accessible unread count. The dot disappears when the conversation is read.
- Contacts: the same header and insets, alphabetical contacts, local search and a header Add contact action. Pending requests remain in Chats.
- Conversation: the same header with Back, the contact name/security status, Security and existing connection options; a compact 32 dp avatar beside the name. Back uses a rounded chevron throughout shared headers. Date separators and incoming/outgoing bubbles remain. Request acceptance and changed-key gates still control the composer.
- New chat: exact-username lookup, with contact-card tools retained in local/demo mode.
- Onboarding: one username and one deliberate create action, with concise device-key information.
- Profiles: the current device profile and name editor, kept in its own screen file.
- Settings: visual Light/Dark/Automatic selectors, privacy information, connection actions and separate developer tools. Manual Sync is here; prekey publication is under Connection details.
- Contact security: identity state, safety-number panel, explicit verification/replacement confirmation and blocking controls.

Unread counts come from encrypted local state. No presence, calls, attachments or typing indicators are invented. Delivered still means recipient storage plus ACK. Accept still does not mean Verified. The local read markers do not alter backend, Signal, network sync or build-type endpoint policy.

## File boundaries

- `ui/theme/Color.kt`: the only raw color palette.
- `Theme.kt`: semantic light/dark Material color roles and theme composition.
- `Type.kt`: Space Grotesk for every Material typography role, with variable font weights 300–700. Safety numbers retain a centrally defined monospaced style for comparisons.
- `Shapes.kt` and `Effects.kt`: shared corner shapes and decorative opacity values.
- `Layout.kt`: common 16 dp page inset, 56 dp minimum header row, 20 dp header icons, 48 dp touch targets with compensated outer insets so visible glyphs align with the 16 dp title/content edges, 4 dp divider spacing and maximum page width.
- `Dimensions.kt`: reusable dimensions for controls and internal content. Screens/components contain no raw dp, font-size or color literals.
- `components/PageHeader.kt`: shared title/back/actions/divider and scrollable PageContent used by onboarding, settings, profiles, new chat and security. Chats and conversation use the same header with lazy-list content; system insets are applied and consumed once at the root Scaffold boundary, so screen-level IME padding excludes navigation-bar space already handled by the root.
- `AvatarColors.kt`: semantic avatar color selection; `Appearance.kt`: persisted Light/Dark/Automatic preference.
- Screens and components consume MaterialTheme roles; appearance previews use the same light/dark schemes. Do not add color hex values, font resources or text-style overrides to screens.

The unchanged font asset is copied from the user's GoXEV project (`app/src/main/res/font/space_grotesk.ttf`). Its embedded copyright identifies the Space Grotesk Project Authors. The SIL Open Font License is bundled in `app/src/main/assets/licenses/space_grotesk_OFL.txt`, sourced from [Google Fonts](https://github.com/google/fonts/blob/main/ofl/spacegrotesk/OFL.txt). No runtime font download is needed. GoXEV itself is not modified.

`ui/components/` owns shared controls, `ui/screens/` each screen, and `ui/navigation/` app navigation. Application/network logic remains outside the theme and UI.

`MessengerDesignTest` checks the chat header, request visibility and reply gating and captures light/dark synthetic previews. `AppearanceTest` checks immediate selection, persistence across preference-store recreation and system-theme policy. Existing emulator tests continue exercising actual navigation, message sending and verification confirmation. Developer screenshots contain synthetic content only.


`ThemeTest` checks the exact dark primary/background, primary-content contrast, and font coverage across every Material typography role in debug and release.

## Local unread state

The top-center count is the number of incoming messages not yet viewed on this device, excluding blocked contacts. Read message IDs are stored in the existing encrypted endpoint repository, separately from Message and delivery receipt data. Opening a STARTED conversation marks its currently stored messages read; incoming messages while it remains open are marked read during refresh. Leaving the route or backgrounding stops that behavior. A deleted message no longer contributes to the count. Existing incoming history without local read markers appears unread until opened.

This never sends read receipts or changes recipient ACK/Delivered behavior. New counts survive process restart. `UnreadMessagesTest` verifies persistence, new arrivals, duplicate polling, deletion and independence from server ACK. `MessengerDesignTest` verifies shared header bounds/back actions, search, new-message labeling and light/dark layouts.

## Error presentation

Routine action errors use a compact red notice with an X, 12 dp space below it, and a five-second display duration. Critical warnings do not auto-dismiss. A successful foreground sync clears prior temporary HTTP 429/503 action errors. Background polling does not repeatedly publish ordinary notices. Cryptographic, invalid-response argument and encrypted-storage failures use a persistent red Action needed panel; dedicated changed-identity warnings and send gates remain unchanged. Presentation does not alter retry, rate-limit or session-renewal behavior.


## Phase 1G.1: local app-access lock

Settings → Privacy → App lock offers Off (default), strong Biometric, PIN, or Biometric + PIN fallback, with Immediately / 30 seconds / 1 minute / 5 minutes measured from backgrounding. Existing installations stay Off. [AndroidX Biometric 1.1.0](https://developer.android.com/jetpack/androidx/releases/biometric) is the current stable release; MainActivity uses FragmentActivity and BiometricPrompt with BIOMETRIC_STRONG only. Android device credential is intentionally not an alternative in this phase. No biometric data or callback details are collected or logged.

AppLockController is process-owned independently of the network runtime. Before loading private UI, AppLockGate waits for encrypted configuration and requires a current grant. Private navigation, content, semantics and Settings are absent from composition while gated; this is not an overlay. A fresh process always requires unlock when enabled. Lifecycle STOP immediately covers content and invalidates authentication attempts; ordinary backgrounding starts a monotonic timeout. Configuration changes preserve an existing unlock grant but cancel incomplete authentication. A timer updates the gate when possible and resume always rechecks elapsed time. No unlocked flag, PIN field or private route is persisted by the lock. Notification actions remain generic; after unlock they select Chats through the normal root. Future deep links must remain inside this gate.

PINs accept 6–64 ASCII digits and require confirmation. The platform SecretKeyFactory PBKDF2WithHmacSHA256 uses 600,000 iterations, a random 16-byte salt and a 32-byte verifier, compared with MessageDigest.isEqual. The fixed, versioned parameter record is validated on load. This phase chooses the platform implementation instead of adding a native Argon2 dependency; see [OWASP password storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html). A six-digit PIN has low entropy even with a KDF; recommend longer PINs. The original Argon2 roadmap remains a future reviewed alternative, not an implemented promise.

Configuration, verifier and retry bookkeeping occupy one record in the existing SQLCipher store. No plaintext PIN, reversible PIN encoding, server PIN, telemetry or identity-provider integration exists. Input is never saved through saved-instance state; fields clear on submission/background/disposal and working character/derived arrays are cleared best-effort. Managed text input may still leave transient memory copies. After two failures, delays progress 5/10/20/40/80/160 seconds up to a five-minute cap. Each attempt is checkpointed before verification; process death cannot reset it. Cooldowns use elapsed time and boot count; reboot conservatively restarts the recorded delay. Storage errors fail closed without recreating identity. No automatic wipe exists.

Changing or disabling an existing lock requires a fresh configured PIN/biometric confirmation (60-second, in-process grant). Biometric enrollment must succeed before saving a biometric mode. PIN modes require confirmation of the new PIN when saving; the same PIN may be re-entered when only changing timing. A configured biometric can authorize replacement of a forgotten PIN. Without a working configured method, there is no recovery or app-provided destructive reset: Android Clear storage/uninstall has its normal explicitly destructive semantics and may lose identity/history. Logout retains the app-lock configuration and is never performed by app unlock; unlock does not reconnect a logged-out account.

**Screen privacy choice B: global FLAG_SECURE**, from MainActivity's first content frame, including lock Off, PIN enrollment, grace periods and prompts. This also protects recent-task previews and blocks supported screenshots/recording. It does not defeat external cameras, malicious accessibility services, a compromised OS or every OEM behavior. Backgrounding removes the private composition even during the grace period. No conversation remains under the lock screen.

**This is UI-lock mode.** The existing AppRuntime/SQLCipher/Keystore protection is unchanged. WorkManager can fetch, decrypt, store and ACK while the app is locked, with the same identity, outbox, deduplication, rate limits and generic Ghost Cloak / New message notifications. A visible lock screen follows the existing all-foreground notification-suppression policy; background locked delivery can notify. The private screen's foreground loop is absent while gated; unlocking restores the existing immediate-resume loop and unchanged cadence. Phase 1G.2 Maximum Security Mode may instead gate local key use and accept background-sync limitations. It is not implemented here.
