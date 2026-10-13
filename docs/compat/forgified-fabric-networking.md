# Forgified Fabric Networking API compatibility

Immersive Portals no longer requires the exact networking module bundled by owo-lib. The optional registration patches check the fields, receiver method descriptors and redirect sites that they use before applying. A different version label alone does not reject the modpack; an incompatible target still fails with the installed version and required target in its diagnostic.

The Minecraft 1.21.1 verification matrix covers:

| Networking module | Source of version | Verification |
| --- | --- | --- |
| 4.2.2+a92978fd19 | owo-lib 0.12.15-beta.12 | Real-jar ABI, receiver concurrency, native UDP/TCP |
| 4.3.0+5c124ecf19 | Forgified Fabric API 0.107.0+2.0.22+1.21.1, included by TxniLib | Real-jar ABI, receiver concurrency, native UDP/TCP |
| 4.3.1+7e3d421f19 | Current 1.21.1 networking release | Real-jar ABI, receiver concurrency, native UDP/TCP, impaired UDP/TCP |

The runtime lanes add the standalone newer module alongside owo-lib's nested baseline so NeoForge must select the newer module. They test the affected networking module, not every feature or bundled module of TxniLib. Nothing new is bundled into the published Immersive Portals jar. The registration lock, duplicate rejection, callback dispatch and setup-state restoration remain in place.

4.3.1+7e3d421f19 itself requires NeoForge 21.1.248 or newer. Its runtime lanes use 21.1.248; the bundled and TxniLib lanes retain 21.1.228. This dependency is enforced by the networking mod's own metadata. Use `IP_VERIFICATION_NEOFORGE=21.1.248` with that newer module when invoking the harness locally.

To verify a newer installed module locally, use `IP_VERIFICATION_NETWORKING=<module-version> python tools/verify.py e2e`, or `./gradlew test -PverificationNetworking=<module-version> --tests '*FabricReceiverRegistrationTest'`. Linux CI additionally uses `IP_NETWORK_STRESS=true` for the port-scoped netem profile. The release fallback verification runs the same transport/API/network matrix as CI, and release evidence requires every lane.

Upstream references: [TxniLib dependencies](https://github.com/txnimc/TxniLib/blob/main/build.gradle.kts), [Forgified Fabric API Maven repository](https://maven.su5ed.dev/releases/org/sinytra/forgified-fabric-api/fabric-networking-api-v1/), [issue #29](https://github.com/UpperMoon0/ImmersivePortals/issues/29).
