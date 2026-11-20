# Development

## Setup

- JDK 21.
- The Android SDK with platform 37 and its build-tools; `ANDROID_HOME` points at it, or
  `local.properties` (gitignored) carries `sdk.dir=<path>`. Gradle installs a missing platform
  once the licences are accepted.
- Nothing else: the Gradle wrapper pins Gradle, and every dependency resolves from Google's Maven
  repository and Maven Central.

## Modules

| Module | Kind | Holds |
|---|---|---|
| `:contract` | JVM | The `@Serializable` models of the server's API and the one JSON configuration (`ContractJson`). No behaviour. |
| `:kit` | JVM | Everything the screens need that is not Android: networking, sign-in, the event streams, stores, and the fixture interceptor that serves canned JSON. |
| `:app` | Android | Compose screens, `AppGraph` (the constructor wiring), the activities, the theme, and the debug source set. |

The split keeps logic where a plain JUnit test reaches it in seconds. A view is thin enough when no
bug in it could be caught by a unit test; the two things that need Android itself (token storage,
the browser leg of sign-in) are interfaces in `:kit` with the real implementation in `:app`.

## Authentication

The server sits behind a login proxy, so a refused request means one of three things, and
`ApiError` tells them apart: a `401` or a bare non-JSON `403` (the proxy rejecting the token) is a
rejected credential; a `403` with a JSON `detail` is the app refusing that one request and leaves the
person signed in; a `401` with the `detail` `device_revoked` (or `device_unrecognised`) means the
server removed this phone from its list of devices, which ends the session without a renewal first
since a new token would belong to the same phone. Every request carries `X-Device-Name`
(`DeviceName` sanitises it to at most 64 characters; `DeviceNames` in `:app` picks it).

- The provider, client id, redirect and scopes are compiled in (`PaiEnvironment`); nothing about the
  sign-in is fetched from the server.
- A signed-in session is an `AuthMode.Oidc` token set. Nothing is renewed ahead of time except a
  token whose provider stated a lifetime; the normal trigger is a refusal. `TokenRefresher` renews
  single-flight (the provider rotates refresh tokens, so two simultaneous renewals would leave one
  caller holding a retired token) and stores the new set before using it. A rejected refresh token
  ends the session; an unreachable provider does not.
- `ApiClient` renews and resends a refused request once; `SseClient` does the same for a stream.
- Two things need Android and are interfaces in `:kit`: `TokenStore` (where the credential survives a
  restart) and `Connectivity` (the device's network path).

## Signing in and the session

`SessionController` (in `:kit`) owns whether the app has a credential and what is built on it: one
`ApiClient`, the event streams (`StreamConnections`) and one `MeStore` per sign-in, closed and
replaced on the next. It is the only place that decides between the signed-out and signed-in screens.

- **Browser sign-in.** The app discovers the provider's endpoints and opens the authorization address
  in a Chrome Custom Tab. The provider redirects to `pai://oauth/callback`, which `RedirectActivity`
  hands to the controller. The controller checks scheme, host and path against the redirect it asked
  for, then `SignInFlow` checks `state` before the code is exchanged. A sign-in the process did not
  survive (killed during the browser leg) reports that it did not complete.
- **Where the credential lives.** `SealedFileTokenStore` writes it to `files/auth.bin`, sealed with
  AES-GCM under a key held in the AndroidKeyStore (`AndroidKeystoreKey`). A file that cannot be
  opened (altered, or its key gone) reads as signed out.
- **Ending the session.** A credential the server refuses for good clears the stored credential and
  shows the sign-in screen with a notice. Signing out does the same without one and then, best effort
  and bounded, with the credential still in memory: ends the phone's own sign-in with
  `POST /api/sign-ins/current/sign-out` (which also drops its push tokens) and asks the provider to
  revoke the sign-in (the `revocation_endpoint` of its discovery document) so the stored refresh token
  stops working. Offline, the phone still signs out at once.

The Robolectric tests cover the screens and the redirect plumbing; the real Keystore and the Custom
Tab need a device or emulator.

## Capabilities

`GET /api/me` says whether the caller is the owner or a guest of one or more namespaces. `MeStore`
turns that into `Capabilities`; until the answer arrives, and for a role the app does not know,
nothing owner-only is offered. The server refuses an owner's routes to a guest regardless.

## Event streams

Two streams exist, both over `SseClient` (reconnect with backoff and jitter, `Last-Event-ID` resume,
a watchdog for a stream silent for three pings, renewal on refusal):

- `GET /api/notifications/stream` is account-wide: `sessions` (the session list changed; run the
  incremental sync), `notification`, `read`, and signals only the owner uses. It carries no history.
- `GET /api/session/{id}/stream` is one transcript: `init` pages of history, `batch`, `status`,
  `processing`, `draft`, `arc`. A connection without a resume point replays the whole history, newest
  page first; `StreamConnections.openSession` takes the entry id to resume from instead.

`SessionEvent` and `NotificationEvent` decode a record; an unknown event name or a body that does not
decode is kept as a value rather than dropped or thrown. A 404 or 410 on a stream is reported once
as `StreamGone` and the stream stays closed. The app going to the background pauses every open
stream and the foreground resumes them (`StreamConnections.pause`, `resume`).

## Checks

```bash
./gradlew ktlintCheck                      # style; ktlintFormat fixes what it can
./gradlew :contract:test :kit:test         # JVM tests, seconds
./gradlew :app:recordRoborazziDebug        # every :app unit test, and writes the screenshots
./gradlew :app:lintDebug :app:assembleDebug
tooling/test-release-scripts.sh            # the release scripts against broken input
```

Pull requests run all of these (`.github/workflows/ci.yml`) behind one aggregate check, `CI ok`.

`:app` unit tests run on the JVM under Robolectric with native graphics. The screenshot tests
write their PNGs under `app/build/outputs/roborazzi/`, but only from the record task;
`testDebugUnitTest` alone passes without writing them. Screenshots are recorded for a reader to
look at, not compared against a baseline. `tooling/screenshots.sh` re-records them and copies them
to `docs/images/`.

## Fixture mode

A debug build started with the launch flag serves canned data from `:kit`'s `Fixtures` instead of
reaching a server:

```bash
adb shell am start -n com.frederikberg.pai/.MainActivity --ez fixture_mode true
```

Release builds ignore the flag.

## Debug server

A debug build listens on `127.0.0.1:8765` for `/health`, `/state` and `/logs?level=WARN`.

```bash
adb forward tcp:8765 tcp:8765
curl localhost:8765/state
```

The server and its in-memory log live in `app/src/debug/` and are not part of a release build.
Debug builds also accept cleartext HTTP to `127.0.0.1` and `localhost`, so a development server
reached through `adb reverse` works without TLS.

## The API contract

The models in `:contract` mirror the TypeScript types the server's web client is written against. A
snapshot of the declarations they cover is vendored under
`contract/src/test/resources/api-contract/`: `types.ts` (comments dropped), `interfaces.txt` (which
declarations it keeps), `guest-routes.txt` (the routes a guest may call) and `SYNCED_AT` (the commit
all of it was taken from).

- `ContractTest` holds the serial names of every registered model equal to the fields of its
  interface, the known values of every wire enum equal to the literals of its union, and
  `interfaces.txt` equal to the set of models registered. A model not registered there is unchecked.
- `RoutesTest` holds every route the client names to the guest route table, method included.

```bash
tooling/sync-contract.sh <backend-sha>
```

is the only writer of the snapshot and needs read access to the backend repository (a logged-in
`gh`, or `GH_TOKEN`).

## Releasing

The version is `appVersionName` in `gradle.properties`; the app's `versionCode` is derived from it
(`major * 10000 + minor * 100 + patch`), so every upgrade is accepted by Android.

1. Move the `[Unreleased]` entries of `CHANGELOG.md` into a dated `[X.Y.Z]` section and set
   `appVersionName` to `X.Y.Z`. `tooling/check_release_versions.sh vX.Y.Z` verifies both.
2. Merge that through a pull request once `CI ok` is green.
3. Tag the merge commit, `git tag -a vX.Y.Z -m "Release vX.Y.Z" && git push origin vX.Y.Z`.

The tag runs `.github/workflows/release.yml`: it re-checks the versions, builds the release APK
signed with the keystore held in the repository's Actions secrets, verifies the signature and the
version inside the APK, and publishes a GitHub Release whose notes are the changelog section. A tag
containing `-` is marked as a pre-release.

The same tag then delivers the build to the channels whose credentials the repository holds; a channel
without its credential is skipped with a notice on the run, and the GitHub Release is published before
any of them:

| Channel | Needs | Delivers |
|---|---|---|
| GitHub Release | the keystore secrets | the signed APK, for Obtainium and direct installs |
| Firebase App Distribution | secrets `GOOGLE_SERVICES_JSON` (names the Firebase app) and `FIREBASE_SERVICE_ACCOUNT_JSON` (a service account with the *Firebase App Distribution Admin* role), variable `FIREBASE_TESTER_GROUPS` (group aliases, comma-separated) | the APK to those testers, with the release notes |
| Google Play internal testing | secret `PLAY_SERVICE_ACCOUNT_JSON` (a Play Developer API service account with release rights), optional variable `PLAY_RELEASE_STATUS` | the app bundle to the internal track |

The release keystore is the Play *upload* key. A release build needs `RELEASE_KEYSTORE_FILE` (a path)
and `RELEASE_KEYSTORE_PASSWORD` in the environment and fails without them rather than producing an
unsigned APK. The key alias is `release`. Debug builds sign with the SDK's debug key.

Updates install over an existing install only when the signature is the same and `versionCode` is
higher, so the keystore is never regenerated casually: a new key means uninstalling the app first.
