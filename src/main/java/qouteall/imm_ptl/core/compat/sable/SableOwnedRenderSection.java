package qouteall.imm_ptl.core.compat.sable;

/** Applied only with Sable installed; ordinary world sections retain a null owner. */
public interface SableOwnedRenderSection {
    void ip_setSableRenderOwner(SableSectionRenderOwner owner);
}
