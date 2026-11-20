# PAI for Android

[![CI](https://github.com/frederikberg/pai-android/actions/workflows/ci.yml/badge.svg)](https://github.com/frederikberg/pai-android/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/frederikberg/pai-android)](https://github.com/frederikberg/pai-android/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Native Android client for PAI Cloud, a personal system that runs Claude Code sessions on a cloud
VM and drives them from a phone. This app is the Android front end for it, the sibling of
[pai-ios](https://github.com/frederikberg/pai-ios).

**This is not an app you can install and use.** It talks to one specific privately-hosted backend,
and that backend is not public. It is here because the parts underneath it are worth reading and
reusing, and because a public repository gets unmetered CI.

## What is worth taking from here

| | Where |
|---|---|
| Sign-in with an OpenID Connect provider by hand: PKCE over a Chrome Custom Tab, a token set sealed with a key that never leaves the Keystore, and a refresh that happens once however many requests were refused together | `kit/src/main/kotlin/com/frederikberg/pai/kit/auth/`, `kit/.../session/` |
| An API client that tells a login proxy's bare 403 from the application's own JSON 403, renews the credential once and sends the request again | `kit/.../net/` |
| Server-sent events over OkHttp with reconnect rules that run in virtual time in tests: backoff with jitter, a watchdog for a quiet stream, resume from the last event id | `kit/.../live/` |
| Models that cannot fail a whole decode over one new value, and a test that holds them to the server's TypeScript types | `contract/` |
| A debug server inside the app, debug builds only, so a script can ask the app what it thinks instead of reading pixels | `app/src/debug/` |

## How it is arranged

Three Gradle modules. Anything a plain JUnit test can catch lives in the two JVM modules and runs
in seconds; the Android module is views and wiring.

| Module | What |
|---|---|
| `:contract` | the `@Serializable` models of the API, no behaviour |
| `:kit` | networking, sign-in, event streams, stores, the fixture interceptor |
| `:app` | Compose screens, the constructor wiring, the activities, the debug source set |

## Signing in

The backend sits behind Zitadel. "Sign in" runs the authorization-code flow with PKCE in a Chrome
Custom Tab against a native, public Zitadel application and comes back through `pai://oauth/callback`.
The token set is sealed in a file under a key held in the AndroidKeyStore. When the backend refuses
the access token, a 401 or the proxy's plain-text 403, the app renews it once and sends the request
again, and signs out only when the renewal itself is refused.

Signing in is the only way in; there is no field for a pasted token. Every request carries the
phone's name in `X-Device-Name`, so the backend's list of signed-in devices can show it, and a device
that list revokes is signed out the next time it asks. Signing out first ends the phone's sign-in on
the backend, then revokes the refresh token at Zitadel.

## Building and checks

JDK 21 and the Android SDK are all it takes.

```bash
./gradlew ktlintCheck :contract:test :kit:test :app:recordRoborazziDebug :app:lintDebug :app:assembleDebug
```

`docs/development.md` has the setup, the fixture mode, the debug server and how a release is cut.

## Docs

- [docs/development.md](docs/development.md): setup, modules, checks, sign-in, event streams, the API
  contract snapshot, fixture mode, the debug server and releasing.

## License

[MIT](LICENSE)
