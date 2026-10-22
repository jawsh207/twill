# Changelog

What's new in each Twill release. The Release workflow copies the section for the version
being released onto its GitHub release page, and refuses to release a version that has no
section here. Write entries for people using the app, newest version first.

## 1.0.9

- New home screen widgets in the same system colours as the app. "Conversations" lists your
  SMS, Nostr and Delta Chat conversations together, newest first, with a button to start a
  chat. "Conversation" shows the latest messages of any one conversation (SMS, Nostr or
  Delta Chat); long-press it to pick a different one. Previews in the widget picker show
  the real look instead of old screenshots, and the app is slightly smaller.

## 1.0.8

- New look that follows your system colours exactly like Settings: grey with "Other colors",
  tinted with "Wallpaper colors", light and dark. Pages use the same background as Settings,
  and rows, cards, incoming bubbles, menus and dialogs the same lighter surface; your accent
  colour is only used for highlights such as buttons, switches and your own bubbles.
- Material 3 Expressive motion.
- Settings screens group their options into rounded cards, like Android's Settings app.

## 1.0.7

- The app icon's background is now blue instead of green.
- Setup and the rest of the app call the app "Twill" instead of "Messaging" (permissions
  screen, share sheet, widgets, notifications and default-SMS-app prompts).
- SMS/MMS notifications have a "Delete" button next to "Mark as read" and "Reply". It deletes
  the messages shown in the notification. (When an MMS needs downloading, "Download" takes its
  place, since Android shows at most three buttons.)

## 1.0.6

- Search inside a conversation: tap the search button at the top of any SMS/MMS, Nostr or
  Delta Chat conversation. Matches are highlighted, and the arrows step through older and
  newer ones, loading earlier messages as needed.
- New app icon: the same two overlapping speech bubbles, now in the bubble shape used by
  Twill's notification icons.

## 1.0.5

- Clearer "Start chat" menu: "SMS/MMS conversation", "Nostr chat or group" and "Delta Chat
  chat or group".
- SMS/MMS has a new icon in the same speech-bubble style as Nostr and Delta Chat, in its
  notifications and in the "Start chat" menu.
- The "Start chat" button uses the same speech-bubble icon, with a plus.

## 1.0.4

- The long-press "Start chat" menu lists each service with its icon in the same colour as
  SMS/MMS. Picking Nostr or Delta Chat then asks whether to start a chat or a group.

## 1.0.3

- The long-press "Start chat" menu groups each service's options under its name, with the
  service's own icon (the same one its notifications use).

## 1.0.2

- Long press the "Start chat" button to choose where to start a chat: SMS/MMS, Nostr or
  Delta Chat. A normal press still starts an SMS/MMS conversation.

## 1.0.1

- Delta Chat Settings now say "relay" instead of "chatmail", matching Delta Chat's own app.

## 1.0.0

First release of Twill, a messaging app based on GrapheneOS Messaging. It installs alongside
the system Messaging app.

- **SMS/MMS** with extra options: delay sending (with a countdown to cancel), scheduled
  messages, keep unread conversations at the top, website link previews (off by default) and
  sending long messages as MMS.
- **Use Twill without SMS:** turn SMS/MMS off and use Twill only for Nostr and Delta Chat,
  with another app handling texts.
- **Nostr private messages** (NIP-17): one-to-one and group chats, sign in with Amber or a
  key stored on the phone, NIP-05 names, attachments and profile pictures.
- **Delta Chat:** encrypted chat over email using the official Delta Chat core. Create a free
  profile, use a setup code, or sign in with an email account.
- One inbox with a collapsible section per protocol, its own notification icon for each, and
  a choice of background delivery (always connected or check every 15 minutes).
