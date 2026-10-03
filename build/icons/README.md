# ReqWS shared application icon

Desktop and the GoLand plugin use the same flat forest-green R and mint repository nodes. The [plugin SVG](../../integrations/goland/src/main/resources/META-INF/pluginIcon.svg) is the single editable artwork source (40 × 40 viewBox).

- [reqws.png](reqws.png): 1024 × 1024 RGBA export of the SVG, with a transparent exterior.
- [reqws.icns](reqws.icns): macOS icon with 16, 32, 128, 256 and 512-point representations at 1× and 2×. Electron Forge consumes it for local and personal-release packages.
- [generate-icons.py](../../scripts/generate-icons.py): regenerates both Desktop assets directly from the plugin SVG.

The design was generated with the built-in `image_gen` tool on 2026-10-03 using the previous GoLand plugin icon as a reference, then transcribed into simple SVG paths and circles to keep small sizes crisp and both products identical. The final generation prompt was:

> Use case: logo-brand. This input is the CURRENT ReqWS GoLand plugin icon, a design reference. Create a refined new shared icon for BOTH ReqWS Desktop and the GoLand plugin, closely inspired by this reference. Retain the strong white geometric uppercase R and a restrained mint green two-node repository motif on its right, on a deep forest green rounded square. Improve the balance, spacing and the readability of the R: clear continuous vertical stem and clean diagonal leg, smooth rounded bowl, generous counter. Keep the node motif simple and smaller than the R, with two circular dots linked by one short vertical stroke. Make it extremely minimal and flat, clean vector-logo appearance using exactly three uniform colors: deep forest green #185449, white #FFFFFF, and pale mint #A6E8C7. No blue, purple, orange, bevel, lighting, 3D, shadows, gradient, gloss, grain, texture or fine detail. Square 1024x1024 canvas with genuine transparent exterior; rounded-square tile inset about 7 percent from all edges and optically centered. One icon only, no name, no text except the R, no additional decoration or mockup. Bold legibility at 16 to 40 px. Deliver the finished asset itself.

To change the icon, edit the plugin SVG and regenerate the Desktop exports. Use Python 3 with CairoSVG 2.9.1, Pillow 12.3.0 and the Cairo runtime; these are optional artwork tools, not application/build dependencies:

```bash
python3 scripts/generate-icons.py
```

Commit the SVG and both exports together. Keep the SVG's 40 × 40 viewBox and transparency. Inspect 16/32/40-pixel exports and the newly packaged macOS app's `Contents/Resources/electron.icns` and `CFBundleIconFile`. Source execution through Electron Forge does not install the app bundle icon; Dock/Finder appearance is checked on the packaged macOS app.
