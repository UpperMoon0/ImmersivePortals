# Immersive Portals - CE

> **Notice:** **Immersive Portals - CE** is an independently community-maintained distribution of Immersive Portals for NeoForge Minecraft 1.21.1. It is not an official release by the original creators.

See through portals and travel between dimensions seamlessly—without a loading screen.

## Features

- Portal-in-portal rendering
- Seamless cross-dimensional travel
- Mirrors and non-Euclidean spaces
- Custom portals through commands and datapacks
- Dimension stacks and world wrapping
- Player scale and gravity transformations
- Modding APIs for portals, dimensions, remote chunks, and world rendering

## Compatibility improvements in this build

This Minecraft 1.21.1 NeoForge Community Edition distribution includes tested Sable 2.0.5 integration for moving sublevels crossing portals and dimension stacks. Sable bodies keep their global identity, velocity and interpolation state across dimensions; riders remain attached; rotated portal camera/gravity transforms are preserved; and remote tracking continues across the portal boundary. The collision integration also avoids duplicate portal collision processing. Sable is optional; ordinary Immersive Portals behavior works without it.

The compatibility path is exercised by automated dedicated-server plus real-client tests over both Sable UDP networking and TCP fallback, including gravity-driven recrossing and rider dismount.

## Optional renderers and shaders

Choose only one terrain renderer. The pinned combinations are vanilla without shaders, Sodium 0.8.12+mc1.21.1 with optional official Iris 1.8.14-beta.1+1.21.1-neoforge, or Embeddium 1.0.15+mc1.21.1 with optional NeOculus 1.8.7. NeOculus requires Embeddium; official Iris requires Sodium. Classic Oculus is not the pinned NeOculus implementation.

Shaderpack regression inputs include MakeUp Ultra Fast 9.4a (`shadowless_high`) and Complementary Reimagined r5.5.1 (`POTATO`). Other packs, presets and pack-specific shadows need separate validation. Normal rendering supports nested portals; compatibility/debug renderers retain a one-layer limit.

Create 6.0.10-280 / Flywheel 1.0.6 renders portal and clipped views through a scoped vanilla block-entity fallback and restores the selected backend for the main view. See the [renderer compatibility contract](https://github.com/UpperMoon0/ImmersivePortals/blob/main/docs/renderer-backends.md) and [6.0.11 release notes](https://github.com/UpperMoon0/ImmersivePortals/blob/main/changelog/6.0.11.md) for scope and verification limits.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.228+
- Java 21
- Cloth Config API 15.0+ for NeoForge

## Attribution & Credits

Immersive Portals was created by **qouteall** and is licensed under Apache-2.0. This Community Edition distribution is maintained independently by **NsTut** and contributors based on the official NeoForge port. It preserves the upstream license and contributor attribution and is not affiliated with or endorsed by the original maintainers.

Source and issues for this build: https://github.com/UpperMoon0/ImmersivePortals

Upstream documentation: https://qouteall.fun/immptl/
