# Use Case 27: Server setup — manifest-driven, launch-adapted dev-tools capability selector (Linux-only)

## Summary
The UC-26 "Select the development tools you want to install" step is rebuilt as a **cursor-driven checkbox selector** — one capability per line, prefixed `[X]`/`[ ]`, a visible cursor, arrow keys + scroll to move, **space** to toggle, **Enter** to commit — implemented in **pure-shell raw mode** (`read -rsn1` + ANSI), with no `whiptail`/`dialog` dependency so the `.deb` TTY auto-onboard path (UC-19) keeps working. The separate UC-22 Android `[Y/n]` toolchain prompt is **fully merged** into this selector, making it the single place tools are configured. Capabilities become a **manifest-driven plugin model**: each is a **sourced shell snippet** at `devtools.d/<id>/manifest.sh` declaring `ID`, a version-bearing `LABEL`, `DEPENDS_ON`, an optional `WARNING`, and launch-adaptation hooks — auto-discovered from the directory, so adding a tool is dropping in a manifest, not editing the wizard. Rather than specializing the build image with per-capability conditional `RUN` blocks, a capability **publishes what to execute so the running container is adapted at spawn** (`.bashrc`/`profile`-style PATH+env wiring plus the toolchain install), keeping the base image generic. The **shell catalog is authoritative**; the Java install-time CLI (`DevToolsConfig`/`DevToolsStep`) reads the same persisted ledger rather than holding a second definition. Selecting a capability auto-selects its transitive `DEPENDS_ON`; deselecting a depended-on capability **prompts to confirm** and cascade-deselects only on confirmation. State persists to a **single `.ai-sandbox-devtools` ledger** — the old `.ai-sandbox-toolchains` is retired with **no migration** (alpha; a clean reinstall is acceptable). The base image standardizes on **glibc (Debian)** for every session so the Android emulator works without a build-time libc flip. Toolchains are **provisioned eagerly at spawn** so a session is ready when handed over. The catalog ships exactly three capabilities: **DinD**, **Java 21** (standalone), and **Android SDK** (`DEPENDS_ON: [java]`, amd64-only); their labels embed the exact installed version from the same constants the install uses. When Java/Android are enabled, their binaries resolve by bare name in **both login and non-login shells** (fixing the entrypoint PATH-overwrite and the missing `build-tools/` entry), with `JAVA_HOME`/`ANDROID_HOME`/`ANDROID_SDK_ROOT` set. Finally, **PowerShell support is removed and the project is documented as Linux-only**, including `PROJECT_BRIEF.md`.

## Acceptance Criteria
1. The devtools step renders as a **pure-shell raw-mode cursor checkbox list**: each capability on its own line prefixed `[X]` (selected) / `[ ]` (unselected), a visible cursor/highlight on the current line, arrow keys **and** scroll move the cursor, **space toggles** the current line, **Enter commits**, and a documented key cancels. The old "type a number then Enter" loop is gone. No `whiptail`/`dialog` dependency is introduced.
2. Capabilities are **auto-discovered, sourced shell manifests** at `devtools.d/<id>/manifest.sh`. Each manifest declares `ID`, a version-bearing `LABEL`, `DEPENDS_ON` (list of other capability ids), an optional `WARNING`, and the launch-adaptation hooks it contributes. Adding a new capability requires only a new manifest directory — **no edits** to the selector loop, commit logic, dependency resolver, or persistence code.
3. A capability **adapts the running container at spawn** rather than specializing the build image: it contributes the install + PATH/env wiring that is applied when the session container is provisioned. The generic base image carries **no per-capability conditional install blocks**.
4. The **shell catalog is the single source of truth**. The Java install-time CLI (`DevToolsConfig`/`DevToolsStep`) consumes the same persisted ledger / discovered manifests and holds **no independent capability list**. If any Java-side mirror is unavoidable, a test guards against drift (ids, deps, versions).
5. **Dependency resolution**: selecting a capability auto-selects its transitive `DEPENDS_ON` set, with auto-selected dependencies visibly marked. Deselecting a capability that another selected capability depends on **prompts to confirm** (e.g. "This will also disable Android. Continue? [y/N]") and cascade-deselects the dependents **only on confirmation**; declining leaves the selection unchanged. The committed selection is never internally inconsistent.
6. The initial catalog ships **exactly** three capabilities: **DinD** (rootless docker-in-docker, the UC-26 capability), **Java 21** (standalone JDK), and **Android SDK** (`DEPENDS_ON: [java]`, amd64-only). All default OFF on a fresh install; `--reconfigure` pre-fills from persisted state.
7. State persists to a **single `.ai-sandbox-devtools` ledger**. `.ai-sandbox-toolchains` is no longer read or written and is removed from the codebase. **No migration code** is added — a pre-existing install is expected to reconfigure from scratch.
8. The UC-22 Android `[Y/n]` toolchain prompt is removed; Android is configured **only** through this selector. There is no double-prompting and one source of persisted truth.
9. Java and Android **labels embed the exact version/components installed** — e.g. "Java 21 (Temurin JDK)" and "Android SDK — platform-tools / build-tools 36.0.0 / android-36" — sourced from the same constants the install uses, so the label and the install can never drift.
10. With **Java** enabled, a freshly spawned session resolves `java -version` and `javac -version` by bare name in **both** a login shell (`sh -lc`) and a non-login shell (`sh -c`), and `JAVA_HOME` is set and correct. With **Android** enabled, a freshly spawned session resolves `adb`, `sdkmanager`, `emulator`, and build-tools binaries (e.g. `aapt2`) by bare name in **both** login and non-login shells; `ANDROID_HOME`/`ANDROID_SDK_ROOT` are set and `build-tools/<ver>` is on PATH. The fix addresses the **entrypoint PATH overwrite**, not just a `profile.d` snippet.
11. The base image standardizes on a **glibc (Debian) base for every session**, so a launch/spawn-adapted Android emulator runs without a build-time libc flip. Android remains **amd64-only** (x86_64 system image / emulator; arm64 is a documented follow-up); on a non-amd64 host the Android entry is shown disabled/unselectable with an explanatory note — never offered-then-broken.
12. Toolchains are **provisioned eagerly at spawn**: `spawn.sh` installs/wires the selected capabilities before handing the session over, so the session is ready immediately and pays no first-use install delay. Selecting/deselecting a capability takes effect for **NEW sessions on the next `spawn.sh`**; existing running sessions are unaffected. When **no** capability is selected, sessions are behaviorally identical to today.
13. **PowerShell support is removed**: `setup.ps1`, `spawn.ps1`, `attach.ps1`, `clean.ps1` (and any other `.ps1` helpers) and their parity requirements are dropped, and the project is documented as **Linux-only**. README and `PROJECT_BRIEF.md` are updated — PowerShell is removed from `stack.languages` and the `.ps1` variants are removed from `build.commands`.
14. The `.deb` TTY auto-onboard path (UC-19) and `--reconfigure` (UC-26 AC#4) both reach the new selector and behave identically to a first-time setup run (reconfigure pre-filling current state).
15. README + onboarding docs explain the unified selector, the manifest-driven capability/dependency model, the version-bearing labels, the eager-at-spawn provisioning, and the PATH guarantees. `PROJECT_BRIEF.md` is updated where documented behavior changes (Linux-only, glibc base, toolchain unification).

## Acceptance Gates (mandatory live Docker verification)

These are mandatory verification gates, **on top of** the acceptance criteria above. They are satisfied only by **spawning real session containers on this host** and exercising the capabilities inside them — unit tests alone do not satisfy them.

- **G1 — Capability matrix (isolation + combined).** Each selection below is configured through the selector, a session is spawned via the real onboarding → `spawn.sh` path, and the capability is verified **live inside the spawned container**:
  1. **DinD only**
  2. **Java only**
  3. **Android only** (implies Java per the dependency model)
  4. **Java + Android + DinD combined**

  For every combination: each enabled capability's binaries resolve by bare name in **both** a login shell (`sh -lc`) and a non-login shell (`sh -c`) with the documented env vars set (per AC#10), and capabilities **not** selected leave **no trace** in that session (no binaries on PATH, no env vars, no daemon, no install). The combined run additionally confirms the three coexist without PATH/env collisions.

- **G2 — Real install/uninstall cycle permitted.** To run G1, the dev-team is **authorized to uninstall and reinstall the ai-sandbox server application on this host as needed** — `.deb` purge + reinstall, image rebuild, and `clean.sh`/`spawn.sh` cycles — so the from-scratch *onboarding → selector → spawn → in-container verification* path is exercised end-to-end against real Docker, not mocked. The agents document each install/uninstall they perform.

- **G-host — Capable execution host (full runtime verification, no SKIPs).** G1 and G2 are executed on a **capable host**, distinct from the host where this use case was authored. The execution host MUST provide what the full runtime exercise needs:
  - **Root-enabled user namespaces** so the rootless DinD daemon actually starts and serves (i.e. NOT `kernel.apparmor_restrict_unprivileged_userns=1`); the DinD verification must show `docker info` / `docker compose ls` succeeding live inside the spawned session — not a `SKIP`.
  - **`/dev/kvm` available** (plus the glibc base) so the Android emulator actually **boots** an AVD, not merely resolves `adb`/`sdkmanager`/`emulator` on PATH.

  No `SKIP`s are permitted for the runtime steps: DinD must genuinely serve and the emulator must genuinely boot on the execution host. (For reference, the authoring host hits two walls that make it unsuitable for execution: `kernel.apparmor_restrict_unprivileged_userns=1` blocks the rootless DinD daemon — per UC-26 — and the QEMU emulator needs `/dev/kvm` + glibc — per UC-22.)

## Potential Pitfalls & Open Questions
- **Risk (base-image port)** — Standardizing on a glibc (Debian) base flips the base image away from Alpine for **every** session. This touches all base package installs (`apk` → `apt`), grows the image, and changes `PROJECT_BRIEF.md`'s `versions` entries (`node: alpine-apk`, `tmux: alpine-apk`, etc.). The dev-team must port the base `SandboxDockerfile` and re-verify the existing non-toolchain session behavior on the new base. This is the largest single ripple in the use case and should be scoped explicitly during analysis.
- **Risk (eager-spawn latency/size)** — Provisioning Java (~JDK) and especially Android (~+1.5 GB SDK) eagerly at spawn makes the first spawn slow and bandwidth-heavy. A persistent cache (the `/workspace/environment-utilities/` precedent from `aisandbox-dind`/`aisandbox-emulator`) should make it a one-time cost per session root; offline / download-failure handling needs a defined behavior (fail the spawn vs warn-and-continue).
- **Risk (raw-mode TTY robustness)** — A pure-shell raw-mode cursor UI must restore the terminal on every exit path (commit, cancel, Ctrl-C, error trap), handle non-TTY / piped stdin gracefully (fall back or refuse cleanly), and work under the `.deb` postinst TTY. Terminal-state leakage is a classic failure mode here.
- **Risk (entrypoint PATH change blast radius)** — Fixing the entrypoint so non-login shells inherit toolchain PATH affects **every** session, not just Java/Android ones. Regression coverage must prove the no-capability case stays byte-identical to today.
- **Risk (PowerShell removal sweep)** — Removing PowerShell is project-wide: ensure no Linux path silently shells out to a `.ps1` helper, and that CI, README, and docs referencing PowerShell are all cleaned up, not just the top-level scripts.
- **Assumption** — DinD retains its UC-26 behavior (rootless daemon, trust-boundary `WARNING` shown at selection time); it is re-expressed as a manifest in the new model but its runtime semantics are unchanged.

## Original Description
> When we implemented UC-26, we screwed up the server installation.
> 1st. The selector for the developer tools is VERY AWKWARD. I'd like to have each line to be selected and markable with [X] or deselected with [ ], and use the scroll to select one or another developer tool.
> Also, java, and Android should be selectable there too.
> If we select android and java we need to make sure the paths are fixed to access them.
> When selecting java or android, the title of the feature must include the version that will be installed

## Clarifications
- Q: How should the new cursor-driven checkbox selector be rendered (it needs arrow/scroll movement + space-to-toggle)?
  A: Pure-shell raw mode (`read -rsn1` + ANSI), no `whiptail`/`dialog` dependency.
- Q: Should the existing UC-22 Android toolchain prompt be fully merged into this one checklist?
  A: Fully merge — Android is configured only in the unified selector.
- Q: How should Java and Android relate in the selector?
  A: Don't hardcode it — build an extensible capability/dependency framework (a strategy pattern) where each capability declares its inter-dependencies and links to the code that applies it to the Docker configuration.
- Q: How deep should the PATH fix go?
  A: Login + non-login shells + build-tools; fix the entrypoint PATH overwrite, set `JAVA_HOME`/`ANDROID_HOME`/`ANDROID_SDK_ROOT`.
- Q: When you deselect a capability that another selected capability depends on, what should happen?
  A: Prompt to confirm, then cascade-deselect on confirmation.
- Q: How much abstraction should the capability/apply-strategy framework have?
  A: Manifest-driven plugin model (self-contained, auto-discovered descriptors).
- Q: Where should the capability registry be single-sourced (shell vs Java)?
  A: Shell authoritative; the Java install-time CLI reads the same persisted ledger.
- Q: What capabilities should this use case ship?
  A: Just DinD + Java + Android.
- Q: What format/location should each capability manifest take?
  A: A sourced shell snippet per capability (`devtools.d/<id>/manifest.sh`).
- Q: How should an image-build capability plug into the build?
  A: Don't put many conditions in the build image; capabilities publish what runs to adapt the container at launch/spawn (the `.bashrc`-style approach), letting the base image be adapted by the plugins.
- Q: How should the two ledgers (`.ai-sandbox-toolchains` / `.ai-sandbox-devtools`) be consolidated?
  A: One ledger (`.ai-sandbox-devtools`); no migration — we're on alpha and a from-scratch reinstall is acceptable.
- Q: How much PowerShell parity for the raw-mode cursor UI?
  A: Remove PowerShell support entirely; document the project as Linux-only from now on.
- Q: How should the base-image libc be handled given Android needs glibc?
  A: Always use a glibc (Debian) base for every session.
- Q: When should a launch-adapted toolchain install?
  A: Eagerly at spawn, so the session is ready when handed over.
- Q: Where should the PowerShell removal / Linux-only change live?
  A: Bundle it into UC-27.
- Q: What verification gates must this use case pass?
  A: Two mandatory live-Docker gates (see Acceptance Gates): (G1) every capability is verified inside a real spawned container both in isolation (DinD, Java, Android) and combined (Java+Android+DinD); (G2) the dev-team is authorized to uninstall/reinstall the application on this host as needed to exercise the full onboarding → spawn → in-container verification path against real Docker.
- Q: How should the gates treat the two known host walls (rootless DinD can't start here; emulator needs /dev/kvm)?
  A: This host is only where the UC is being authored; the gates are executed on a CAPABLE host (root-enabled user namespaces + /dev/kvm). Full runtime verification is mandatory there — DinD must actually serve and the emulator must actually boot; no SKIPs for the runtime steps.
