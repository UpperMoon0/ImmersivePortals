# Forgified Fabric network registration

The pinned owo-lib bundles Forgified Fabric networking 4.2.2+a92978fd19.
Its native receiver factory calls NeoForge `NetworkRegistry.register` from mod
constructors, temporarily changing the shared `NetworkRegistry.setup` flag.
NeoForge 21.1.228 stores configuration and play registrations in ordinary
`HashMap`s. Parallel constructors can corrupt those maps before login.

Main CI run 37796490738 rejected the Iris pack-disabled client's login in
`NetworkRegistry.initializeNeoForgeConnection`, line 360, with
`End size 269 is less than fixed size 270`. The client stayed on the disconnect
screen until the 6020-second graphical timeout. This failure happened before
the intended shader-pack negative assertion.

When Fabric networking is loaded, CE serializes native handler creation across
both protocol registrars using the NeoForge registry class monitor. It also
uses concurrent NeoForge payload maps and atomic duplicate rejection. The
existing Fabric payload-type registry protection remains necessary upstream.
The guard captures the original setup flag while holding that shared lock and
restores it in `finally`, including when the upstream registration factory throws.

The regression registers distinct receivers concurrently through both
protocol registrars, verifies repeated receivers reuse handlers, checks shared
factory exclusion and enumerates every payload using the login stream path.
The harness separately fails immediately on a server-reported disconnect,
preserving scoped thread dumps and the original reason. A disconnect is never
accepted as a successful negative control.
It tracks the server log's file offset and only processes newly appended complete
lines, retaining partial UTF-8 bytes across polls and resetting on truncation or
replacement. Successive events are consumed once; the latest disconnect in a
new batch supplies the failure reason. Normal shutdown still requires all result
markers and a successful client exit code.

## Receiver registration and publication (issue #21)

The exact supported module is `fabric-networking-api-v1` **4.2.2+a92978fd19**,
embedded in owo-lib 0.12.15-beta.12. The compatibility plugin rejects other
versions before applying these optional mixins and checks that each target exists.
The required mixin signatures target `NeoNetworkRegistrar.registeredPayloads`
and its `NeoPayloadHandler` inner class: `globalReceivers`/`localReceivers` are
`Map` fields; `registerGlobalHandler(PacketFlow,Object,Function,TriConsumer)` and
`registerLocalReceiver(ICommonPacketListener,Object,Function,TriConsumer)` return
boolean. These contracts were checked against the pinned module bytecode.

All three maps use concurrent storage. Registration alone locks its particular
receiver map while running upstream's contains-key/construct/put operation;
the global and local maps have independent monitors. Packet dispatch, lookup and
unregistration retain the upstream methods and use atomic concurrent-map reads
and removal, with no registration monitor on packet handling. Duplicate calls
return false without replacing the winner; unregister returns the removed
handler and permits subsequent replacement. Already-enqueued packets retain the
receiver snapshot captured by the original handler.

`FabricReceiverRegistrationTest` executes the actual pinned handler's dispatch,
lookup and unregister methods through the production registration wrappers.
Synchronized-start workers register both flows of one payload, race duplicate
flows/listeners, and register/dispatch/remove distinct local listeners concurrently.
The earlier native-factory/NeoForge channel-enumeration regressions remain required.
