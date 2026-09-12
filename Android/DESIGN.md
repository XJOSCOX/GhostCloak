# Messenger design

The theme follows the local GoXEV reference: Space Grotesk typography, muted green accents and neutral charcoal surfaces. Dark primary is exactly `#94B86F`, with background `#1A1A1A` and surface `#222222`. Light mode uses a darker green for text/button contrast. Avatars use the semantic brand container, without unrelated color palettes. Chat, Contact, Profiles and Settings have separate bottom-navigation destinations. Settings offers persistent Light, Dark and Automatic choices; Automatic is the default and follows Android's system appearance. System-bar icons also follow the selected appearance. The app uses local vector icons and initials; reference photos, artwork and unavailable feature controls are not copied into the product.

Layout references: [Inbox Chat App](https://dribbble.com/shots/24369393-Inbox-Chat-App) for inbox hierarchy and [Material canonical layouts](https://m3.material.io/foundations/layout/canonical-examples/overview) for list/detail organization.

## Screens

- Chats: With no unread messages, a compact “Chats” title and search/compose icons on the right. When unread messages exist, Search moves left, the centered count replaces “Chats”, and Compose stays right. Only unread incoming messages contribute to the count. Search toggles a local conversation-name field; closing it clears the query. The header has no brand icon or second title. Existing avatars stay in conversation rows.
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
- `components/PageHeader.kt`: shared title/back/actions/divider and scrollable PageContent used by onboarding, settings, profiles, new chat and security. Chats and conversation use the same header with lazy-list content; system insets remain owned by the root Scaffold.
- `AvatarColors.kt`: semantic avatar color selection; `Appearance.kt`: persisted Light/Dark/Automatic preference.
- Screens and components consume MaterialTheme roles; appearance previews use the same light/dark schemes. Do not add color hex values, font resources or text-style overrides to screens.

The unchanged font asset is copied from the user's GoXEV project (`app/src/main/res/font/space_grotesk.ttf`). Its embedded copyright identifies the Space Grotesk Project Authors. The SIL Open Font License is bundled in `app/src/main/assets/licenses/space_grotesk_OFL.txt`, sourced from [Google Fonts](https://github.com/google/fonts/blob/main/ofl/spacegrotesk/OFL.txt). No runtime font download is needed. GoXEV itself is not modified.

`ui/components/` owns shared controls, `ui/screens/` each screen, and `ui/navigation/` app navigation. Application/network logic remains outside the theme and UI.

`MessengerDesignTest` checks the chat header, request visibility and reply gating and captures light/dark synthetic previews. `AppearanceTest` checks immediate selection, persistence across preference-store recreation and system-theme policy. Existing emulator tests continue exercising actual navigation, message sending and verification confirmation. Developer screenshots contain synthetic content only.


`ThemeTest` checks the exact dark primary/background, primary-content contrast, and font coverage across every Material typography role in debug and release.

## Local unread state

The top-center count is the number of incoming messages not yet viewed on this device, excluding blocked contacts. Read message IDs are stored in the existing encrypted endpoint repository, separately from Message and delivery receipt data. Opening a STARTED conversation marks its currently stored messages read; incoming messages while it remains open are marked read during refresh. Leaving the route or backgrounding stops that behavior. A deleted message no longer contributes to the count. Existing incoming history without local read markers appears unread until opened.

This never sends read receipts or changes recipient ACK/Delivered behavior. New counts survive process restart. `UnreadMessagesTest` verifies persistence, new arrivals, duplicate polling, deletion and independence from server ACK. `MessengerDesignTest` verifies shared header bounds/back actions, search, new-message labeling and light/dark layouts.
