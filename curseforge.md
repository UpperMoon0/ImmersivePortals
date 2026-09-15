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

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.228+
- Java 21
- Cloth Config API

## Attribution & Credits

Immersive Portals was created by **qouteall** and is licensed under Apache-2.0. This Community Edition distribution is maintained independently by **NsTut** and contributors based on the official NeoForge port. It preserves the upstream license and contributor attribution and is not affiliated with or endorsed by the original maintainers.

Source and issues for this build: https://github.com/UpperMoon0/ImmersivePortals

Upstream documentation: https://qouteall.fun/immptl/
