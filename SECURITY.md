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

What the app keeps, and how, is described here as each part lands. So far it keeps
nothing — it does not talk to a hub yet — and it is built to keep whatever it later
stores out of cloud backups and device-to-device transfers.

## Releases

Releases are built by GitHub Actions from a `v*` tag on `main`, from the tagged source alone
with no build cache, after the same checks as every pull request. Each is signed with one key,
held in the secrets of an environment only those tags can use and in an offline backup, and
never in this repository; CI fails if a keystore is ever tracked. Each release states the
SHA-256 of its APK and of the signing certificate, which is the same for every release:
[Installing](README.md#installing) shows how to check both.
