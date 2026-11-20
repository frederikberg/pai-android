# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Signing in through the identity provider in a Chrome Custom Tab, with a token set sealed under a key
  in the Keystore, renewal on refusal, a per-device name on every request, and sign-out that ends the
  phone's sign-in on the server and at the provider.
- The models of the guest-reachable API, checked against a vendored copy of the server's TypeScript types.
- A client for every route a guest may call that the app uses, and the account-wide and per-session
  event streams over server-sent events.
- A signed-in screen naming the person and their role, and settings with the theme and sign-out.
