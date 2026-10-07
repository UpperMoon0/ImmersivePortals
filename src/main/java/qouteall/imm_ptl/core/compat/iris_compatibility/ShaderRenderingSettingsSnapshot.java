package qouteall.imm_ptl.core.compat.iris_compatibility;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * A temporary copy of Iris's global material/mesh settings, including reloadRequired.
 * Reflection keeps the Sodium and NeOculus/Embeddium vertex-format ABIs isolated.
 * The providers replace their material maps, so their original references must be retained.
 */
final class ShaderRenderingSettingsSnapshot {
    private static final ClassValue<Field[]> FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .peek(field -> field.setAccessible(true)).toArray(Field[]::new);
        }
    };

    private final Object settings;
    private final Object[] values;

    private ShaderRenderingSettingsSnapshot(Object settings, Object[] values) {
        this.settings = settings;
        this.values = values;
    }

    static ShaderRenderingSettingsSnapshot capture(Object settings) {
        Field[] fields = FIELDS.get(settings.getClass());
        Object[] values = new Object[fields.length];
        try {
            for (int i = 0; i < fields.length; i++) values[i] = fields[i].get(settings);
        }
        catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot capture shader rendering settings", exception);
        }
        return new ShaderRenderingSettingsSnapshot(settings, values);
    }

    void restore() {
        Field[] fields = FIELDS.get(settings.getClass());
        try {
            for (int i = 0; i < fields.length; i++) fields[i].set(settings, values[i]);
        }
        catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot restore shader rendering settings", exception);
        }
    }
}
