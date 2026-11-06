# Nova Image Studio

- Upstream: https://github.com/tianjiangqiji/nova-image-studio
- Pinned commit: `7768f3f8d7f47e04c6d18572837a086c7a533161`
- Upstream version: `3.1.3`
- License: AGPL-3.0 (see `LICENSE`)

PicPilot keeps the upstream feature implementation and applies an embedded adapter for branding, authentication-aware task routing, model configuration, and billing confirmation.

Modification notice (2026-11-06): the vendored frontend includes PicPilot branding,
authentication-aware routing, model and billing adapters, and embedded UI changes.
These derived files remain under AGPL-3.0. The canvas bridge, proxy configuration,
build scripts and full pinned upstream source must accompany corresponding-source
delivery. The root MIT license does not relicense this directory.

The source distribution is prepared by `scripts/export-canvas-source.py`; see
`docs/canvas-source.md`. Preserve existing upstream notices and provide access to
the exact modified version, not just a link to the original upstream repository.
