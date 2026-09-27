# Disposable Compose S0 probes

The source inputs are fixed Git commit `384894dab0e8dcc7c0040b08802eb3bd167a3c85`
and either `probe.patch` (Compose) or `swing.patch` (instrumented Swing baseline).
They are never applied by the production build. Do not publish these ZIPs.

From the repository root, run `python3 scripts/stage_compose_probe.py` for Compose,
or add `--variant swing` for the baseline. Each invocation creates a private new
source directory outside Git. `--output` must name a new directory outside Git;
the checkout, its index and existing directories are preserved. Staging neither
resolves dependencies nor starts tests or an IDE.

Compose adds the aligned compiler, bundled modules, required descriptor dependency,
a minimal Jewel content root and two independent semantic tests. Swing retains the
original UI and business behavior, adding only listener lifetime counters. Both
include the same host performance method: three new IDE processes, a first-open
measurement, 30-second warm-up, 60-second idle sampling at five-second intervals,
20 hide/show cycles, 20 content recreations, another warm-up and idle sample.

The local runner consumes an explicit ZIP and reuses the owned-profile and
exact-archive checks. Compose also requires the opt-in artifact check before launch.
Use `--scenario performance --variant swing` or `--scenario performance --variant compose`
for measurements. Freeze the Swing-derived budget before the Compose comparison.
The default scenario is Compose input and content recreation. Optional
`--foreground-handshake` waits for a controller to inspect and raise the isolated
window, then create `foreground-ready` in the printed private run directory.
The controller must observe `ready-for-input` first. The handshake does not invoke
any UI callback; Driver still sends the actual click and keyboard input.

The host clears only the test plugin's `ide.performance.screenshot` option: its
periodic component-to-BufferedImage paint path fails with the bundled Metal renderer.
Diagnostics use `takeFullScreenshot` (actual AWT screen pixels) before/after each
performance round and before real input. All IDE errors still fail the run; no
rendering flags, production code or exception suppressions implement this adapter.

All remote interfaces and performance sampling stay in test source sets. Neither
these reports nor a static screenshot replace the retained nine-process integration
suite. Commands, evidence and the G0 decision are in the
[S0 verification record](../../../docs/changes/goland-compose-rebuild/s0-verification-2026-09-26.md).
