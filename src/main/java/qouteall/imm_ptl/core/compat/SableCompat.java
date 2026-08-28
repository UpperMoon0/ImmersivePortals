package qouteall.imm_ptl.core.compat;

import net.neoforged.fml.loading.LoadingModList;

/**
 * Runtime detection of the Sable mod for structurally selecting collision
 * wrapper wiring. When Sable is present, the Sable compat mixin is applied
 * (via IPCompatMixinPlugin) and owns the portal collision hook; the base
 * Entity wrapper then passes through untouched so exactly one effective
 * wrapper runs per movement.
 */
public final class SableCompat {
    public static final boolean ACTIVE = detect();

    private SableCompat() {}

    private static boolean detect() {
        try {
            return LoadingModList.get().getModFileById("sable") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
