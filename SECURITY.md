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
