# Security Policy

Môme DM is a device-owner app: on a child's phone it holds the strongest
management privileges Android grants a non-system app. Please treat security
reports here as you would for any device-management tool.

## Reporting a vulnerability

**Do not open a public issue.** Use GitHub's private reporting:

1. Go to the repository's **Security** tab → **Report a vulnerability**, or
2. Contact the maintainer (@nosari20) privately through GitHub.

Please include what you were able to do, on what Android version, and the steps
to reproduce. A proof of concept helps enormously — for example, the command
sequence that let an app launch while the device was supposed to be locked.

You will get an acknowledgement as quickly as the maintainer can manage. This is
a volunteer project, not a vendor with an on-call rota; please be patient, and
please give a reasonable window before public disclosure.

## What is in scope

Anything that breaks one of the app's actual promises:

- Escaping a complete lock or child mode from the child's device without the
  parent PIN (launching a non-allowed app, ending lock task, reaching Settings).
- Recovering the parent PIN, or bypassing its verification or its lockout.
- Impersonating a parent to a child device, or a child to a parent — forging,
  replaying, or tampering with BLE messages after the handshake.
- Recovering the shared secret from anything other than the provisioning QR
  code while it is on screen.
- Making a child device report a lock state that does not match reality.

## What is known and out of scope

These are documented design limits, not vulnerabilities — though a report that
makes one materially worse is welcome:

- **The provisioning QR code carries the shared secret.** Anyone who photographs
  it during the provisioning window learns it. The QR screen is sensitive while
  it is displayed.
- **One shared secret per controller, not per device.** Compromising one child
  device's secret exposes others provisioned by the same parent until the parent
  regenerates it and re-provisions. Per-device secrets are planned.
- **No BLE link-layer encryption.** LE Secure Connections pairing is not used;
  the application-layer HMAC handshake and per-message MAC are the trust
  boundary. Traffic is authenticated and replay-resistant, not confidential.
- **Physical access wins.** A factory reset removes the device owner, and the
  app cannot prevent that. This is a parenting tool, not an anti-theft or
  anti-tamper product.
- **Restricted Mode and DNS filtering are not guarantees.** Where the app
  applies content filtering, it hides most objectionable content, not all.

## Supported versions

The project is pre-1.0 and only `main` receives fixes.
