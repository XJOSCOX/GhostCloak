# Messenger design

The September 2026 redesign uses an off-white canvas, white conversation rows, restrained blue accents and a matching graphite dark appearance. Chats, Contacts and Settings have separate bottom-navigation destinations. Settings offers persistent Light, Dark and Automatic choices; Automatic is the default and follows Android's system appearance. System-bar icons also follow the selected appearance. The app uses local vector icons and initials; reference photos, artwork and unavailable feature controls are not copied into the product.

Layout references: [Inbox Chat App](https://dribbble.com/shots/24369393-Inbox-Chat-App) for inbox hierarchy and [Material canonical layouts](https://m3.material.io/foundations/layout/canonical-examples/overview) for list/detail organization.

## Screens

- Chats: local name search, All/Requests filters, real request counts, previews and timestamps. Requests are separated from regular conversations. The floating New chat action starts a conversation. Search stays in memory and makes no directory/network query.
- Contacts: an alphabetical list of existing contacts, local search and an Add contact action. Pending requests remain in Chats.
- Conversation: compact avatar header, safety control, date separators, incoming/outgoing bubbles and an icon send button. Tap or long-press a bubble for local deletion. The options menu retains manual Sync. Request acceptance and changed-key gates still control the composer.
- New chat: exact-username lookup, with contact-card tools retained in local/demo mode.
- Onboarding: one username and one deliberate create action, with concise device-key information.
- Settings: profile editing, visual Light/Dark/Automatic selectors, privacy information, connection actions and separate developer tools. Manual Sync is here; prekey publication is under Connection details.
- Contact security: identity state, safety-number panel, explicit verification/replacement confirmation and blocking controls.

No unread counters, presence, calls, attachments or typing indicators are invented. Delivered still means recipient storage plus ACK. Accept still does not mean Verified. This is a presentation change; backend, Signal, network sync and build-type endpoint policy are unchanged.

## File boundaries

`ui/theme/Theme.kt` owns light/dark color schemes and shapes; `Color.kt` holds shared dark palette tokens and `Type.kt` typography. `Appearance.kt` owns the appearance preference and system-theme selection. Only the nonsensitive mode name is stored in its SharedPreferences file. `ui/components/` contains vector icons, avatars, rows, panels and shared controls, including `AppearanceSelector.kt`. Each screen remains in `ui/screens/`, with app navigation in `ui/navigation/`. Application/network logic remains outside UI files.

`MessengerDesignTest` checks search/filter behavior and request reply gating and captures light/dark synthetic previews. `AppearanceTest` checks immediate selection, persistence across preference-store recreation and system-theme policy. Existing emulator tests continue exercising actual navigation, message sending and verification confirmation. Developer screenshots contain synthetic content only.
