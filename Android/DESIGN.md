# Messenger design

The September 2026 redesign follows the supplied messenger references: a white canvas, restrained green accents, compact conversation rows, clear search/filter controls and a matching graphite dark appearance. The app follows Android's system appearance. It uses local vector icons and initials; reference photos, artwork and unavailable feature controls are not copied into the product.

## Screens

- Chats: local name search, All/Requests filters, real request counts, previews and timestamps. The plus icon starts a new chat. Search stays in memory and makes no directory/network query.
- Conversation: compact avatar header, safety control, date separators, incoming/outgoing bubbles and an icon send button. Tap or long-press a bubble for local deletion. The options menu retains manual Sync. Request acceptance and changed-key gates still control the composer.
- New chat: exact-username lookup, with contact-card tools retained in local/demo mode.
- Onboarding: one username and one deliberate create action, with concise device-key information.
- Settings: profile editing, privacy/appearance information, connection actions and separate developer tools. Manual Sync is here; prekey publication is under Connection details.
- Contact security: identity state, safety-number panel, explicit verification/replacement confirmation and blocking controls.

No unread counters, presence, calls, attachments or typing indicators are invented. Delivered still means recipient storage plus ACK. Accept still does not mean Verified. This is a presentation change; backend, Signal, network sync and build-type endpoint policy are unchanged.

## File boundaries

`ui/theme/Theme.kt` owns light/dark color schemes and shapes; `Color.kt` holds shared dark palette tokens and `Type.kt` typography. `ui/components/` contains vector icons, avatars, rows, panels and shared controls. Each screen remains in `ui/screens/`, with app navigation in `ui/navigation/`. Application/network logic remains outside UI files.

`MessengerDesignTest` checks search/filter behavior and request reply gating and captures light/dark synthetic previews. Existing emulator tests continue exercising actual navigation, message sending and verification confirmation. Developer screenshots contain synthetic content only.
