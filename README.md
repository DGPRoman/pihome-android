# pihome-android

Android client for [pihome-hub](https://github.com/DGPRoman/pihome-hub), the HTTP control
plane for a Raspberry Pi wired to relay-switched circuits.

It is the phone's counterpart to [pihome-hub-web](https://github.com/DGPRoman/pihome-hub-web):
the relays, what the sensors last reported, whether the hub can still reach its devices, and
the rules wiring them together — plus what a browser cannot offer, a Quick Settings tile, a
home-screen widget and launcher shortcuts. Kotlin and Jetpack Compose.

> **Status: skeleton.** The build, its checks and CI are in place, and so is the client for the
> hub's API, tested against recorded replies. The app shows a single screen in English or
> Ukrainian and does not use the client yet — see [Roadmap](#roadmap).

## Design notes

**Two modules, split by what they need.** `:hub-client` is plain Kotlin with no Android in
it: everything that talks to the hub, tested on the JVM in seconds. `:app` is everything
Android. The client still runs inside the app, so it is compiled against the Java 17 API,
and the app's lint reads it too, against the app's own minimum Android version.

**Android 9 and later, built for Android 17.** `minSdk` 28, `targetSdk` 37.

**The same failures as the web client.** The hub client sorts every failure into the web
client's kinds — offline, timed out, refused, not the hub, and so on — so the two say the
same thing about the same fault. Reads are asked again at most twice; a write is sent once.
A POST is marked so that OkHttp cannot quietly repeat it either, because each of the hub's
POSTs, from a login to an invitation being redeemed, must not happen twice. Redirects are
never followed: the hub never sends one, so one is something else answering, such as a
captive portal.

**Plain `http://` only at home.** A hub on the home network is usually reached without TLS.
The client allows that only while every address the name resolves to is private — loopback,
RFC 1918, `100.64.0.0/10`, link-local or IPv6 unique local — checked as it connects, so a
name that starts resolving elsewhere is refused. Anything else needs `https://`.

**Nothing leaves the phone.** Cloud backups and device-to-device transfers are off. A new
phone joins with an invitation of its own rather than inheriting another's session.

**One product with the web client.** Colours come from the web client's design tokens, light
or dark as the system is set, and not from the wallpaper. The strings are in English and
Ukrainian, and Android lets the language be chosen for this app alone.

## Development

Android Studio (Quail 3 or later), or a JDK 17+ with an Android SDK that has platform 37 —
point `ANDROID_HOME` at it, or let Android Studio write `local.properties`.

```bash
./gradlew spotlessApply                          # format
./gradlew spotlessCheck lint test assembleDebug  # what CI checks
./gradlew installDebug                           # onto a connected phone or emulator
```

The tests run on the JVM: plain unit tests, and Compose UI tests on Robolectric, so neither
CI nor a contributor needs an emulator. Lint treats warnings as errors. Commit messages
follow Conventional Commits, checked by a hook shared with the sibling repositories — see
[CONTRIBUTING.md](CONTRIBUTING.md).

## Project layout

```
app/                           the Android application
├── build.gradle.kts           SDK levels, lint policy, dependencies
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── kotlin/io/github/dgproman/pihome/
    │   │   ├── MainActivity.kt    the one activity
    │   │   └── ui/theme/Theme.kt  colours from the web client's tokens
    │   └── res/                   strings in two languages, icon, backup rules
    └── test/                      Robolectric and Compose tests
hub-client/                    plain Kotlin: everything that talks to the hub
└── src/main/kotlin/io/github/dgproman/pihome/hub/
    ├── HubClient.kt           one call per route the app uses
    ├── Transport.kt           timeouts, headers, status codes, sent-once writes
    ├── HubAddress.kt          what may be typed in as a hub, and the http rule
    ├── HomeNetwork.kt         which addresses count as home
    ├── InvitationLink.kt      the /join#token links the web client builds
    └── Session.kt, House.kt, Accounts.kt   what the hub answers
gradle/libs.versions.toml      every dependency version, exact
.githooks/commit-msg           the commit rule
```

## Roadmap

| Phase | Scope                                                           | Issue          | Status     |
| ----- | --------------------------------------------------------------- | -------------- | ---------- |
| 1     | Build, checks and CI                                            | #1             | ✅ done    |
| 2     | Hub client, tested against recorded replies and a real hub      | #2, #3         | 🚧 partly  |
| 3     | App shell: theme, navigation, session storage                   | #4             | planned    |
| 4     | Connecting: invitation, password, local-network permission      | #5             | planned    |
| 5     | The house: relays, sensors, devices, rules                      | #6             | planned    |
| 6     | People and invitations, for an admin                            | #7             | planned    |
| 7     | Quick Settings tile, home-screen widget, launcher shortcuts     | #8, #9, #10    | planned    |
| 8     | Signed releases                                                 | #11            | planned    |

## License

[MIT](LICENSE)
