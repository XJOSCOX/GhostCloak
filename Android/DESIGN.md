# Messenger design

The theme follows the local GoXEV reference: Space Grotesk typography, muted green accents and neutral charcoal surfaces. Dark primary is exactly `#94B86F`, with background `#1A1A1A` and surface `#222222`. Light mode uses a darker green for text/button contrast. Avatars use the semantic brand container, without unrelated color palettes. Chat, Contact, Profiles and Settings have separate bottom-navigation destinations. Settings offers persistent Light, Dark and Automatic choices; Automatic is the default and follows Android's system appearance. System-bar icons also follow the selected appearance. The app uses local vector icons and initials; reference photos, artwork and unavailable feature controls are not copied into the product.

Layout references: [Inbox Chat App](https://dribbble.com/shots/24369393-Inbox-Chat-App) for inbox hierarchy and [Material canonical layouts](https://m3.material.io/foundations/layout/canonical-examples/overview) for list/detail organization.

## Screens

- Chats: Solid theme background throughout, matching GoXEV’s home canvas, with slightly contrasting cards and no header color wash. Compact Ghost Cloak header with reduced top/side padding and smaller top-right compose/brand icons. A fading divider separates the header from the small Chats label. The compose action uses an unfilled 20 dp square-and-pencil icon matching the brand icon size, with a 48 dp touch target. No search or filter controls. Conversation previews and incoming requests share the list; request labels and acceptance gating are preserved.
- Contacts: an alphabetical list of existing contacts, local search and an Add contact action. Pending requests remain in Chats.
- Conversation: compact avatar header, safety control, date separators, incoming/outgoing bubbles and an icon send button. Tap or long-press a bubble for local deletion. The options menu retains manual Sync. Request acceptance and changed-key gates still control the composer.
- New chat: exact-username lookup, with contact-card tools retained in local/demo mode.
- Onboarding: one username and one deliberate create action, with concise device-key information.
- Profiles: the current device profile and name editor, kept in its own screen file.
- Settings: visual Light/Dark/Automatic selectors, privacy information, connection actions and separate developer tools. Manual Sync is here; prekey publication is under Connection details.
- Contact security: identity state, safety-number panel, explicit verification/replacement confirmation and blocking controls.

No unread counters, presence, calls, attachments or typing indicators are invented. Delivered still means recipient storage plus ACK. Accept still does not mean Verified. This is a presentation change; backend, Signal, network sync and build-type endpoint policy are unchanged.

## File boundaries

- `ui/theme/Color.kt`: the only raw color palette.
- `Theme.kt`: semantic light/dark Material color roles and theme composition.
- `Type.kt`: Space Grotesk for every Material typography role, with variable font weights 300–700. Safety numbers retain a centrally defined monospaced style for comparisons.
- `Shapes.kt` and `Effects.kt`: shared corner shapes and decorative opacity values.
- `AvatarColors.kt`: semantic avatar color selection; `Appearance.kt`: persisted Light/Dark/Automatic preference.
- Screens and components consume MaterialTheme roles; appearance previews use the same light/dark schemes. Do not add color hex values, font resources or text-style overrides to screens.

The unchanged font asset is copied from the user's GoXEV project (`app/src/main/res/font/space_grotesk.ttf`). Its embedded copyright identifies the Space Grotesk Project Authors. The SIL Open Font License is bundled in `app/src/main/assets/licenses/space_grotesk_OFL.txt`, sourced from [Google Fonts](https://github.com/google/fonts/blob/main/ofl/spacegrotesk/OFL.txt). No runtime font download is needed. GoXEV itself is not modified.

`ui/components/` owns shared controls, `ui/screens/` each screen, and `ui/navigation/` app navigation. Application/network logic remains outside the theme and UI.

`MessengerDesignTest` checks the chat header, request visibility and reply gating and captures light/dark synthetic previews. `AppearanceTest` checks immediate selection, persistence across preference-store recreation and system-theme policy. Existing emulator tests continue exercising actual navigation, message sending and verification confirmation. Developer screenshots contain synthetic content only.


`ThemeTest` checks the exact dark primary/background, primary-content contrast, and font coverage across every Material typography role in debug and release.
