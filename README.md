# pihome-android

Android client for [pihome-hub](https://github.com/DGPRoman/pihome-hub), the HTTP control
plane for a Raspberry Pi wired to relay-switched circuits.

It is the phone's counterpart to [pihome-hub-web](https://github.com/DGPRoman/pihome-hub-web):
the relays, what the sensors last reported, whether the hub can still reach its devices, and
the rules wiring them together — plus what a browser cannot offer, a Quick Settings tile, a
home-screen widget and launcher shortcuts. Kotlin and Jetpack Compose.

> **Status: shows and switches the house, and lets an admin invite people.** A phone joins a
> hub by opening an invitation, or an admin logs in with a password. The app then shows the
> relays as switches, what each sensor last reported and how long ago, whether the hub can
> reach its devices, and the automation rules, read every ten seconds while the screen is
> open. An admin adds people and hands them an invitation as a QR code or a link. A Quick
> Settings tile switches one relay without opening the app. The widget and shortcuts are next;
> see [Roadmap](#roadmap).

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

**Checked against the hub itself.** Recorded replies prove the client matches what its
author believed the hub says. A second suite runs the same client against a real hub —
installed from its lockfile, on loopback, with throwaway keys and the mock relay backend —
through one journey: logging in, switching relays, a sensor reading firing a rule, an admin
inviting a second phone in, changing its role, disabling and deleting it. Pull requests run
it against a pinned hub commit, and once a week it runs against the hub's `main`, to notice
the two drifting apart.

**Plain `http://` only at home.** A hub on the home network is usually reached without TLS,
so Android's own block on it is lifted, and the client takes its place: it allows plain
`http://` only while every address the name resolves to is private — loopback,
RFC 1918, `100.64.0.0/10`, link-local or IPv6 unique local — checked as it connects, so a
name that starts resolving elsewhere is refused. Anything else needs `https://`.

**An invitation opens the app from the phone's camera.** An admin shows an invitation as a QR
code holding the hub's own `/join#…` link. Scanned with the phone's camera, it opens the hub's
join page, which on Android hands the invitation to this app as a `pihome://join?…` link, or
offers the app for download from the hub when it is not installed yet. Nothing goes through a
store or a third party, and the app itself has no scanner, no camera permission and no Google
Play services. An `https` App Link would skip the browser, but Android verifies those against
a public domain, which a hub at home does not have. A link can also be pasted.

**An invitation is spent once, so it is sent once.** The hub spends an
invitation on its first use, refused or not, so the app names the hub and waits for Join,
never sends one twice, and tells a refusal, an invitation that ran out and a reply that never
came apart. Before an invitation or a password is sent anywhere, the address has to answer
the hub's health check, so a typing mistake or a Wi-Fi sign-in page gets neither.

**Asking for the local network only when it is needed.** From Android 17 an app needs
permission to reach devices on the network the phone is on, and a connection made without
it hangs rather than fails. The app looks up where the hub is before it connects, explains
and asks only for an address on the local network, and when the answer was no, says so with
a way to the settings rather than reporting the hub as switched off.

**The session is checked as the app comes into view.** Each time, the app asks the hub who
the session belongs to: a refusal ends it at once, and a new role or a later expiry is kept.
Asked from the home network, that is also what renews the session. Signing out forgets the
session on the phone first and then ends it on the hub, so it never waits for a hub that is
out of reach.

**Nothing leaves the phone.** Cloud backups and device-to-device transfers are off. A new
phone joins with an invitation of its own rather than inheriting another's session. No Google
library is in the app to send anything either, and a test fails if a dependency brings one.

**One secret, encrypted by the Keystore.** The session token is stored in DataStore encrypted
with AES-GCM under a key that lives in the Android Keystore and cannot be read out of it, with
the hub's address bound to it. A token that no longer decrypts — the Keystore lost its key,
or the file was tampered with — means signed out, not a crash.

**Signed in or not is decided above the screens.** Being signed out and being signed in each
have their own back stack, and the session gate chooses between them. Losing the session
takes every signed-in screen away at once, with whatever it was polling, so nothing keeps
asking the hub with a session it has refused: the hub counts each refusal against the phone's
address.

**A switch moves when it is pressed, and only the hub moves it back.** A press shows at once
and is undone if the hub refuses it — but only while the switch still shows that press, so
an answer that arrives late cannot undo something newer. When the answer is lost rather than
a refusal, the switch stays where it was pressed and says it is not sure, because the circuit
may well have switched. A read of the relays that began before a press, or ends during one,
is dropped, so a poll can never put a switch back. Every press ends with a read of its own.

**Polling costs nothing when nobody is looking.** The house is read every ten seconds while
its screen is in view and not at all otherwise. The relays are read first, on their own,
because the hub counts a refused session against the phone's address: an ended session
costs one refusal, not four. A failed read leaves the last answer on screen, under a note
saying it is the last one the hub gave.

**Every reading has its own age.** A sensor's "last seen" moves with any reading, so it cannot
say whether a particular one is current. Each reading is judged by its own time against the
sensor's window, and one that has no time is neither current nor stale.

**An invitation is shown once, and held only while it is.** The token the hub issues is kept in
the people screen's memory: not in the saved screen state, not on disk, and gone with the
screen. Leaving does not withdraw it, and the screen says so. The QR code is drawn on the
phone, and the link is copied marked sensitive, so Android's clipboard preview hides it. The
hub does not say when an invitation is used; the account just stops listing it. So the list
is read while the screen is open, and the invitation is marked used only by a read sent after
it was issued and answered before it ran out, which is how the web client tells used from
expired.

**A tile acts only for somebody who could act in the app.** The Quick Settings tile switches one
relay, chosen in the app. On a locked phone it asks for the phone to be unlocked first, so
whoever picks up somebody else's phone cannot open the gate with it. With no session, a
viewer's account, or no leave to reach the home network, it says so and a tap opens the app;
it sends nothing it knows would be refused or would hang. Each request gets six seconds, a
switch with no answer in that time says it is not sure, and a refused session ends it for the
whole app, as on any screen. A switch still under way when the shade closes finishes, and the
tile shows how it ended.

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
scripts/contract-test.sh                         # against a real hub, see below
```

The tests run on the JVM: plain unit tests, and Compose UI tests on Robolectric, so neither
CI nor a contributor needs an emulator. Lint treats warnings as errors. Commit messages
follow Conventional Commits, checked by a hook shared with the sibling repositories — see
[CONTRIBUTING.md](CONTRIBUTING.md).

The contract tests need a checkout of [pihome-hub](https://github.com/DGPRoman/pihome-hub)
beside this one, or `PIHOME_HUB_DIR` pointing at one, and Python 3.11 or later. The script
installs the hub into a virtualenv under `build/`, starts a hub of its own on a free loopback
port, runs `:hub-client:contractTest` and stops it again; nothing is read from the hub
checkout's `.env` or database.

## Project layout

```
app/                           the Android application
├── build.gradle.kts           SDK levels, lint policy, dependencies
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── kotlin/io/github/dgproman/pihome/
    │   │   ├── AppGraph.kt        everything the app is made of, wired by hand
    │   │   ├── Hubs.kt            where a hub client comes from, one HTTP client for all
    │   │   ├── MainActivity.kt    the one activity
    │   │   ├── connect/           joining, logging in, invitations arriving, the local network
    │   │   ├── house/             the house as the hub reports it: polling, presses, ages
    │   │   ├── people/            accounts and invitations, for an admin, and the QR code
    │   │   ├── quick/             the tile, and what it shares with the widget and shortcuts to come
    │   │   ├── session/           the encrypted session, and the gate above the screens
    │   │   └── ui/
    │   │       ├── PihomeApp.kt   signed out or in, each with its own back stack
    │   │       ├── connect/       what the connecting screens share
    │   │       ├── house/         relays, sensors, devices and rules on screen
    │   │       ├── people/        accounts, adding a person, and the invitation on screen
    │   │       ├── screens/       one file per screen
    │   │       └── theme/         colours from the web client's tokens
    │   └── res/                   strings in two languages, icons, backup rules
    └── test/                      Robolectric and Compose tests
hub-client/                    plain Kotlin: everything that talks to the hub
└── src/
    ├── main/kotlin/io/github/dgproman/pihome/hub/
    │   ├── Hub.kt             every call the app makes, as an interface
    │   ├── HubClient.kt       the same over HTTP, one call per route
    │   ├── Transport.kt       timeouts, headers, status codes, sent-once writes
    │   ├── HubAddress.kt      what may be typed in as a hub, and the http rule
    │   ├── HomeNetwork.kt     which addresses count as home, and need the permission
    │   ├── InvitationLink.kt  /join#token links, and the pihome://join links the join page sends
    │   └── Session.kt, House.kt, Accounts.kt   what the hub answers
    ├── test/                  against recorded replies
    └── contractTest/          against a running hub, and the house it is started with
scripts/contract-test.sh       starts that hub and runs them
gradle/libs.versions.toml      every dependency version, exact
.githooks/commit-msg           the commit rule
```

## Roadmap

| Phase | Scope                                                           | Issue          | Status     |
| ----- | --------------------------------------------------------------- | -------------- | ---------- |
| 1     | Build, checks and CI                                            | #1             | ✅ done    |
| 2     | Hub client, tested against recorded replies and a real hub      | #2, #3         | ✅ done    |
| 3     | App shell: theme, navigation, session storage                   | #4             | ✅ done    |
| 4     | Connecting: invitation, password, local-network permission      | #5             | ✅ done    |
| 5     | The house: relays, sensors, devices, rules                      | #6             | ✅ done    |
| 6     | People and invitations, for an admin                            | #7             | ✅ done    |
| 7     | Quick Settings tile, home-screen widget, launcher shortcuts     | #8, #9, #10    | tile done  |
| 8     | Signed releases                                                 | #11            | planned    |

## License

[MIT](LICENSE)
