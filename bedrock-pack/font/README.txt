RaidAttack font hook
====================

Drop a Bedrock bitmap font here to render the menu (and all Bedrock text) in the
RaidAttack typeface. This folder is a scaffold only — no glyphs ship yet, so the
client keeps Minecraft's default font.

To enable a custom font:
  1. Add a glyph sheet named `default8.png` here (a 16x16 grid of glyphs; this
     replaces Bedrock's default Latin font). Extra unicode pages go in
     `glyph_<HEX>.png` (e.g. glyph_E1.png).
  2. Bump the manifest version (header + module) so Bedrock re-downloads the pack.
  3. Repackage and redeploy (see ../README.md).

Note: replacing default8.png changes EVERY Bedrock text element, not just the /ra
menu. The wide RAID ATTACK wordmark logo (textures/ui/raidattack_logo.png) is the
brand mark used in the menu banner and is independent of this font.
