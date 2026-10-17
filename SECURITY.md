# Security Policy

## Supported versions

Only the latest release is supported. Fixes land on `main` and ship in the next tag.

## Reporting a vulnerability

Open a [private security advisory](https://github.com/RYF5584/WXDBDump-Android/security/advisories/new)
or contact the maintainer directly (see the README). Please do not open a public issue for
anything that could expose a user's data.

Include what you did, what happened, and what you expected. A log captured with
`tools/logcat.sh` helps; it contains paths and key lengths, never key material or message
content.

## Threat model

This tool reads WeChat's private databases on a device the user controls. It is built for
someone extracting their **own** data from their **own** phone. It is not designed to
resist a hostile device.

### What the design does protect against

- **Other devices on the network.** The Xposed module's server binds to `127.0.0.1` only.
  Nothing is reachable from the LAN or over USB.
- **A fake module.** The app proves it is talking to the real module through an HMAC
  challenge-response, so it never has to trust a process that merely opened the port.
- **Passive log readers.** Keys are logged by length and source only; the key material and
  message content never reach logcat.

### What it does not protect against

**The loopback handshake does not authenticate the app.** `Channel.TOKEN` is a build-time
constant, and this repository is public, so any app on the same device that has read the
source can complete the handshake and issue `KEY` or `DUMP`.

Both directions of the handshake must now pass before a command is accepted, which raises
the bar from "any app that scans for open ports" to "an app written against this protocol".
That is not the same as authentication.

Closing the gap needs a secret that is unique per installation. The app and the module run
under different uids (the module runs inside WeChat's process, the app in its own), so a
shared file is not straightforward.

There is a likely route: LSPosed's "New XSharedPreferences" lets an app hand a preference
file to the hooked process through a redirect it controls. That this works here is *not*
verified, and neither is whether the file ends up readable only by the two parties
involved, which is what would make it a real secret. It needs a test before anyone relies
on it — do not assume it from this paragraph.

Until then, the handshake filters peers that have not read this repository. It does not
authenticate the app, and it is not implemented today.

### Practical guidance

- The Xposed channel is meant for a phone whose installed apps you trust.
- Prefer **root mode** if that does not describe your device. It reads through `su` and
  never opens a socket.
- Uninstall or disable the module when you are not using it.

## Scope

Out of scope: issues that require an already-compromised device or root shell (an attacker
with root can read the same databases directly), and anything that requires physical access
to an unlocked phone.
