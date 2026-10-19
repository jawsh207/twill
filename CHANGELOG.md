# Changelog

What's new in each Twill release. The Release workflow copies the section for the version
being released onto its GitHub release page, and refuses to release a version that has no
section here. Write entries for people using the app, newest version first.

## 1.0.5

- Clearer "Start chat" menu: "SMS/MMS conversation", "Nostr chat or group" and "Delta Chat
  chat or group".

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
