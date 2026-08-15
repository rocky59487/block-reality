Block Reality — Demo v0
=======================

Two files matter:

  blockreality-*.jar   the Forge mod          -> <instance>/mods/
  br-sidecar[.exe]     the structural engine  -> <instance>/     (game directory)

Minecraft 1.20.1 + Forge 47.x.

Install with:   ./install.sh <instance>      or   install.bat <instance>

The engine is a separate process, not a library. The mod finds it automatically:
config -> -Dbr.sidecar -> BR_SIDECAR -> game directory -> PATH.
Without it the mod still loads and plays; analysis is off and it says so.

In game: creative tab "Block Reality" -> Structural Steel, Stress Glasses.
Type /br status if anything looks wrong. It reports where it looked for the engine.
