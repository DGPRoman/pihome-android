# Security

This app switches mains-voltage circuits in a physical home through the hub it talks
to. Whoever can use it as you can operate your house.

## Reporting a vulnerability

Please report privately rather than opening a public issue: use
[GitHub's private vulnerability reporting](https://github.com/DGPRoman/pihome-android/security/advisories/new)
on this repository. A response should be expected within a week. This is a personal
project with no commercial support and no bug bounty.

## Scope

This repository is the Android client. The hub has its own threat model and deployment
advice in [pihome-hub's SECURITY.md](https://github.com/DGPRoman/pihome-hub/blob/main/SECURITY.md),
and a weakness in the hub belongs there.

## What the app keeps

- **The session token**, encrypted with a key in the Android Keystore that never leaves it,
  with the hub's address bound in so a token copied next to another address does not decrypt.
  A token that no longer decrypts reads as signed out; the person signs in again.
- **The hub's address and who the session belongs to**, as they are: neither is a secret, and
  the app shows both.
- **The relay chosen for the tile**, and **what the widget last showed**: relay names and
  states, sensor readings, and when they were read.
- **The relays' names, in the launcher's shortcuts**, which the launcher keeps.

It never keeps a password, which the app sends once to log in and the phone's own password
manager may remember, or an invitation, which lives only on the screen that shows it. Nothing
it keeps goes into a cloud backup or a transfer to a new phone. Signing out, or the hub ending
the session, removes the token, the shortcuts and what the widget showed; the tile keeps its
choice, which applies only on the hub it was made on.

## Releases

Releases are built by GitHub Actions from a `v*` tag on `main`, from the tagged source alone
with no build cache, after the same checks as every pull request. Each is signed with one key,
held in the secrets of an environment only those tags can use and in an offline backup, and
never in this repository; CI fails if a keystore is ever tracked. Each release states the
SHA-256 of its APK and of the signing certificate, which is the same for every release:
[Installing](README.md#installing) shows how to check both.
