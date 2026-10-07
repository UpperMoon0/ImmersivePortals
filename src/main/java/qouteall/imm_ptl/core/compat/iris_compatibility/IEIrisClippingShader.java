package qouteall.imm_ptl.core.compat.iris_compatibility;

/** Marks only Iris shader instances whose final vertex-producing stage was transformed. */
public interface IEIrisClippingShader {
    boolean ip_hasClippingEquation();
}
