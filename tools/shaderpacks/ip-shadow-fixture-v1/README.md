# Owned shadow-scope fixture

This fixture uses a deterministic orthographic light looking down negative Z.
Its actual shadow map is 256×256. A caster at Z=1..2 is entirely on the excluded
side of the destination plane; a receiver front face at Z=-3 must remain shadowed.
The light's near/far planes are Z=+4/-4, inside the cleared Z=-5..8 slab, so
unrelated natural Nether terrain cannot occlude the receiver. Receiver and caster
depths must be exactly 0.875 and 0.25 (within readback tolerance).
The uncluttered receiver is green and a shadow is blue; leaked caster geometry is red.
The test enters the real shadow call with portal clipping enabled. Immediately
before each terrain draw a development-only uniform records the actual GL bit:
clip distance is +1 when disabled and -1 when enabled. The production scope must
suspend it. A minimal Mesa llvmpipe EGL reproduction also discarded geometry
for runtime-negative output when the bit reported disabled, so the fixture emits
neutral output then; this is an observed test-driver behavior, not a claim about
general OpenGL semantics. A development-only negative re-enables the bit inside that scope and
must lose both the caster's depth pixels and its visible receiver shadow.
No external shaderpack code or assets are included.
