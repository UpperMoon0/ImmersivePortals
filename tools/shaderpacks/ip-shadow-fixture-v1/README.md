# Owned shadow-scope fixture

This fixture uses a deterministic orthographic light looking down negative Z.
Its actual shadow map is 256×256. A caster at Z=1..2 is entirely on the excluded
side of the destination plane; a receiver front face at Z=-3 must remain shadowed.
The uncluttered receiver is green and a shadow is blue; leaked caster geometry is red.
The shadow vertex shader deliberately emits clip distance -1. The test enters the
real shadow call with portal clipping enabled, so the production shadow scope must
suspend it. A development-only negative re-enables the bit inside that scope and
must lose both the caster's depth pixels and its visible receiver shadow.
No external shaderpack code or assets are included.
