package io.github.headlesshq.headlessmc.lwjgl.redirections;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.headlesshq.headlessmc.lwjgl.api.Redirection;
import io.github.headlesshq.headlessmc.lwjgl.api.RedirectionManager;

/**
 * Fixes the generic type erasure issue with StructBuffer.get() methods.
 * <p>
 * In LWJGL, {@code StructBuffer<T extends Struct<T>, SELF>} has methods like
 * {@code get(int)} that return {@code T}. Due to type erasure, the bytecode
 * return type is {@code Struct}. When the transformer replaces the method body,
 * it creates a bare {@code Struct} instance instead of the correct subclass
 * (e.g. {@code STBTTPackRange}). Callers that cast to the expected subclass
 * then fail with a {@link ClassCastException}.
 * <p>
 * This redirection resolves the actual element type from the generic
 * superclass hierarchy of the Buffer instance and creates the correct type.
 */
public class StructBufferRedirection {
    private static final Map<Class<?>, Class<?>> ELEMENT_TYPE_CACHE = new ConcurrentHashMap<>();

    /**
     * StructBuffer.get(int) returns T erased to Struct.
     */
    public static final String GET_INDEX_DESC = "Lorg/lwjgl/system/StructBuffer;get(I)Lorg/lwjgl/system/Struct;";

    /**
     * StructBuffer.get() returns T erased to Struct.
     */
    public static final String GET_DESC = "Lorg/lwjgl/system/StructBuffer;get()Lorg/lwjgl/system/Struct;";

    public static void redirect(RedirectionManager manager) {
        Redirection getRedirection = (obj, desc, type, args) -> {
            if (obj == null) {
                return null;
            }

            Class<?> elementType = resolveElementType(obj.getClass());
            if (elementType != null) {
                try {
                    java.lang.reflect.Constructor<?> ctr = elementType.getDeclaredConstructor();
                    ctr.setAccessible(true);
                    return ctr.newInstance();
                } catch (ReflectiveOperationException e) {
                    // fall through to default
                }
            }

            // Fallback: return a Struct (same as before)
            return null;
        };

        manager.redirect(GET_INDEX_DESC, getRedirection);
        manager.redirect(GET_DESC, getRedirection);
    }

    /**
     * Resolves the actual Struct element type T from a StructBuffer subclass.
     * <p>
     * For example, for {@code STBTTPackRange.Buffer extends
     * StructBuffer<STBTTPackRange, Buffer>}, this returns
     * {@code STBTTPackRange.class}.
     */
    private static Class<?> resolveElementType(Class<?> bufferClass) {
        return ELEMENT_TYPE_CACHE.computeIfAbsent(bufferClass, clazz -> {
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                Type genericSuper = current.getGenericSuperclass();
                if (genericSuper instanceof ParameterizedType) {
                    ParameterizedType pt = (ParameterizedType) genericSuper;
                    Type rawType = pt.getRawType();
                    if (rawType instanceof Class<?>
                            && "org.lwjgl.system.StructBuffer"
                                    .equals(((Class<?>) rawType).getName())) {
                        Type[] typeArgs = pt.getActualTypeArguments();
                        if (typeArgs.length > 0
                                && typeArgs[0] instanceof Class<?>) {
                            return (Class<?>) typeArgs[0];
                        }
                    }
                }

                current = current.getSuperclass();
            }

            return null;
        });
    }

}
