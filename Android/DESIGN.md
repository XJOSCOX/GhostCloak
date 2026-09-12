# Messenger design

The September 2026 redesign uses an off-white canvas, white conversation rows, blue compose/navigation accents, a semibold wordmark and a matching graphite dark appearance. Soft blue, lilac, teal and amber initials give contacts distinct decorative colors; these never indicate online presence or security state. Chat, Contact, Profiles and Settings have separate bottom-navigation destinations. Settings offers persistent Light, Dark and Automatic choices; Automatic is the default and follows Android's system appearance. System-bar icons also follow the selected appearance. The app uses local vector icons and initials; reference photos, artwork and unavailable feature controls are not copied into the product.

Layout references: [Inbox Chat App](https://dribbble.com/shots/24369393-Inbox-Chat-App) for inbox hierarchy and [Material canonical layouts](https://m3.material.io/foundations/layout/canonical-examples/overview) for list/detail organization.

## Screens

- Chats: Compact Ghost Cloak header with reduced top/side padding and smaller top-right compose/brand icons. A fading divider separates the header from the small Chats label. The compose action uses an unfilled 20 dp square-and-pencil icon matching the brand icon size, with a 48 dp touch target. No search or filter controls. Conversation previews and incoming requests share the list; request labels and acceptance gating are preserved.
- Contacts: an alphabetical list of existing contacts, local search and an Add contact action. Pending requests remain in Chats.
- Conversation: compact avatar header, safety control, date separators, incoming/outgoing bubbles and an icon send button. Tap or long-press a bubble for local deletion. The options menu retains manual Sync. Request acceptance and changed-key gates still control the composer.
- New chat: exact-username lookup, with contact-card tools retained in local/demo mode.
- Onboarding: one username and one deliberate create action, with concise device-key information.
- Profiles: the current device profile and name editor, kept in its own screen file.
- Settings: visual Light/Dark/Automatic selectors, privacy information, connection actions and separate developer tools. Manual Sync is here; prekey publication is under Connection details.
- Contact security: identity state, safety-number panel, explicit verification/replacement confirmation and blocking controls.

No unread counters, presence, calls, attachments or typing indicators are invented. Delivered still means recipient storage plus ACK. Accept still does not mean Verified. This is a presentation change; backend, Signal, network sync and build-type endpoint policy are unchanged.

## File boundaries

`ui/theme/Theme.kt` owns light/dark color schemes and shapes; `Color.kt` holds shared dark palette tokens and `Type.kt` typography. `AvatarColors.kt` owns decorative avatar palettes. A faint blue wash behind the inbox header adds depth without extra controls. `Appearance.kt` owns the appearance preference and system-theme selection. Only the nonsensitive mode name is stored in its SharedPreferences file. `ui/components/` contains vector icons, avatars, rows, panels and shared controls, including `AppearanceSelector.kt`. Each screen remains in `ui/screens/`, with app navigation in `ui/navigation/`. Application/network logic remains outside UI files.

`MessengerDesignTest` checks the chat header, request visibility and reply gating and captures light/dark synthetic previews. `AppearanceTest` checks immediate selection, persistence across preference-store recreation and system-theme policy. Existing emulator tests continue exercising actual navigation, message sending and verification confirmation. Developer screenshots contain synthetic content only.
