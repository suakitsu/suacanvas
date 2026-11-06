# Third-party notices

The root MIT license covers original OpenImage code only. It does not replace
upstream copyright notices, licenses or the conditions applicable to a combined
or derived work. An iframe, separate directory or container alone does not
establish that software is legally independent.

| Component | Source/version | License and scope |
| --- | --- | --- |
| Nova Image Studio | https://github.com/tianjiangqiji/nova-image-studio at `7768f3f8d7f47e04c6d18572837a086c7a533161` (3.1.3) | AGPL-3.0; `third_party/nova-image-studio/`, including modified frontend; preserve its [license](third_party/nova-image-studio/LICENSE) and [modification notice](third_party/nova-image-studio/UPSTREAM.md). |
| Infinite Canvas | https://github.com/basketikun/infinite-canvas at `705fdd9b2f7bfe6fd58156fd5c1cdb1ad251c767` (0.14.0) | AGPL-3.0 at this pinned revision; fetched source and derived canvas distribution. See [license](third_party/infinite-canvas/LICENSE) and [notice](third_party/infinite-canvas/UPSTREAM.md). |
| Gradle wrapper | `clients/picpilot-android*/gradlew*` and wrapper distributions | Apache-2.0; existing upstream headers remain applicable. |
| Noto Sans CJK SC Regular | `notofonts/noto-cjk` at `f8d157532fbfaeda587e826d4cd5b21a49186f7c` | SIL Open Font License 1.1; unchanged font, copyright and provenance in [fonts/NOTICE.md](openImage/src/main/resources/fonts/NOTICE.md), with [full license](openImage/src/main/resources/fonts/OFL-1.1.txt). |
| Package dependencies and runtime images | Maven POMs, npm lockfiles, Cargo/Gradle/NuGet manifests and Dockerfiles | Each component retains its own license. This file is not a transitive dependency license inventory or a binary distribution clearance. |

Modified AGPL applications must retain the required notices and provide the
Corresponding Source, including relevant modifications and build/install scripts,
under the applicable AGPL terms. When users interact with a modified AGPL version
over a network, section 13 requires a prominent source offer. A link to an
unmodified upstream project or an inaccessible private repository is insufficient
for the modified version.

For a combined or derived application covered by AGPL, the applicable AGPL
obligations apply to the covered work as a whole; this notice is not an exemption.
OpenImage's original MIT material is available under MIT, while recipients must
also satisfy applicable AGPL conditions when distributing the combined work.

See [canvas-source.md](docs/canvas-source.md) for the source export and delivery
procedure. Demo images and trademarks are addressed in [ASSET_NOTICE.md](ASSET_NOTICE.md).

Primary references: [GNU AGPL v3](https://www.gnu.org/licenses/agpl-3.0.html),
[MIT text](https://opensource.org/license/mit).
