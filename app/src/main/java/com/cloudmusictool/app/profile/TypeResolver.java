package com.cloudmusictool.app.profile;

/** JVM 描述符 -> Class，供 MountResolver 使用。 */
public final class TypeResolver {

    private TypeResolver() {
    }

    public static Class<?> resolve(String descriptor, ClassLoader cl) {
        if (descriptor == null || descriptor.isEmpty()) {
            return null;
        }
        try {
            switch (descriptor.charAt(0)) {
                case 'Z':
                    return boolean.class;
                case 'B':
                    return byte.class;
                case 'C':
                    return char.class;
                case 'S':
                    return short.class;
                case 'I':
                    return int.class;
                case 'J':
                    return long.class;
                case 'F':
                    return float.class;
                case 'D':
                    return double.class;
                case 'V':
                    return void.class;
                case 'L':
                    if (descriptor.length() < 3 || descriptor.charAt(descriptor.length() - 1) != ';') {
                        return null;
                    }
                    return Class.forName(descriptor.substring(1, descriptor.length() - 1)
                            .replace('/', '.'), false, cl);
                case '[':
                    return Class.forName(descriptor.replace('/', '.'), false, cl);
                default:
                    return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static Class<?>[] resolveAll(String[] descriptors, ClassLoader cl) {
        if (descriptors == null) {
            return null;
        }
        Class<?>[] out = new Class<?>[descriptors.length];
        for (int i = 0; i < descriptors.length; i++) {
            out[i] = resolve(descriptors[i], cl);
            if (out[i] == null) {
                return null;
            }
        }
        return out;
    }
}
