package de.tr7zw.changeme.nbtapi.wrapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import de.tr7zw.changeme.nbtapi.NBTType;
import de.tr7zw.changeme.nbtapi.NbtApiException;
import de.tr7zw.changeme.nbtapi.iface.NBTHandler;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import de.tr7zw.changeme.nbtapi.wrapper.NBTTarget.Type;

public class ProxyBuilder<T extends NBTProxy> implements InvocationHandler {

    private static final Map<Method, Function<Arguments, Object>> METHOD_CACHE = new ConcurrentHashMap<>();

    private final Class<T> target;
    private final ReadWriteNBT nbt;

    public ProxyBuilder(ReadWriteNBT nbt, Class<T> target) {
        if (!target.isInterface()) {
            throw new NbtApiException("A proxy can only be built from an interface! Check the wiki for examples.");
        }
        this.target = target;
        this.nbt = nbt;
    }

    @SuppressWarnings("unchecked")
    public T build() {
        T inst = (T) Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class[] { target }, this);
        inst.init();
        return inst;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        METHOD_CACHE.computeIfAbsent(method, m -> ProxyBuilder.createFunction((NBTProxy) proxy, m));
        return METHOD_CACHE.get(method).apply(new Arguments(target, (NBTProxy) proxy, nbt, args));
    }

    private static class Arguments {
        Class<?> target;
        NBTProxy proxy;
        ReadWriteNBT nbt;
        Object[] args;

        public Arguments(Class<?> target, NBTProxy proxy, ReadWriteNBT nbt, Object[] args) {
            this.target = target;
            this.proxy = proxy;
            this.nbt = nbt;
            this.args = args;
        }
    }

    @SuppressWarnings("unchecked")
    private static Function<Arguments, Object> createFunction(NBTProxy proxy, Method method) {
        if ("toString".equals(method.getName()) && method.getParameterCount() == 0
                && method.getReturnType() == String.class) {
            return (arguments) -> arguments.nbt.toString();
        }
        if (method.isDefault()) {
            return (arguments) -> DefaultMethodInvoker.invokeDefault(arguments.target, arguments.proxy, method,
                    arguments.args);
        }
        Type action = getAction(method);
        if (action == Type.SET) {
            String fieldName = getNBTName(method);
            return (arguments) -> setNBT(arguments.nbt, arguments.proxy, fieldName, arguments.args[0]);
        }
        if (action == Type.GET) {
            Class<?> retType = method.getReturnType();
            String fieldName = getNBTName(method);
            // Allow stacking of proxies
            if (retType.isInterface() && NBTProxy.class.isAssignableFrom(retType)) {
                return (arguments) -> {
                    if (arguments.nbt.hasTag(fieldName) && arguments.nbt.getType(fieldName) != NBTType.NBTTagCompound) {
                        throw new NbtApiException("Tried getting a '" + retType + "' proxy from the field '" + fieldName
                                + "', but it's not a TagCompound!");
                    }
                    return new ProxyBuilder<NBTProxy>(arguments.nbt.getOrCreateCompound(fieldName),
                            (Class<NBTProxy>) retType).build();
                };
            }
            NBTHandler<Object> handler = (NBTHandler<Object>) proxy.getHandler(retType);
            if (handler != null) {
                return (arguments) -> handler.get(arguments.nbt, fieldName);
            }
            return (arguments) -> arguments.nbt.getOrNull(fieldName, retType);
        }
        throw new IllegalArgumentException(
                "The method '" + method.getName() + "' in '" + method.getDeclaringClass().getName()
                        + "' can not be handled by the NBT-API. Please check the Wiki for examples!");
    }

    private static Type getAction(Method method) {
        NBTTarget target = method.getAnnotation(NBTTarget.class);
        if (target != null) {
            if (target.type() == Type.GET && method.getParameterCount() == 0) {
                return Type.GET;
            }
            if (target.type() == Type.SET && method.getParameterCount() == 1) {
                return Type.SET;
            }
        }
        if (method.getName().startsWith("set") && method.getParameterCount() == 1) {
            return Type.SET;
        }
        if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
            return Type.GET;
        }
        return null;
    }

    private static String getNBTName(Method method) {
        NBTTarget target = method.getAnnotation(NBTTarget.class);
        if (target != null) {
            return target.value();
        }
        return method.getName().substring(3).toLowerCase();
    }

    private static Object setNBT(ReadWriteNBT nbt, NBTProxy proxy, String key, Object value) {
        // welcome to the "I wish we all could use java 17" method. Thanks, legacy mc
        // versions
        if (value == null) {
            nbt.removeKey(key);
        } else if (value instanceof Boolean) {
            nbt.setBoolean(key, (Boolean) value);
        } else if (value instanceof Byte) {
            nbt.setByte(key, (Byte) value);
        } else if (value instanceof Short) {
            nbt.setShort(key, (Short) value);
        } else if (value instanceof Integer) {
            nbt.setInteger(key, (Integer) value);
        } else if (value instanceof Long) {
            nbt.setLong(key, (Long) value);
        } else if (value instanceof Float) {
            nbt.setFloat(key, (Float) value);
        } else if (value instanceof Double) {
            nbt.setDouble(key, (Double) value);
        } else if (value instanceof byte[]) {
            nbt.setByteArray(key, (byte[]) value);
        } else if (value instanceof int[]) {
            nbt.setIntArray(key, (int[]) value);
        } else if (value instanceof long[]) {
            nbt.setLongArray(key, (long[]) value);
        } else if (value instanceof String) {
            nbt.setString(key, (String) value);
        } else if (value instanceof UUID) {
            nbt.setUUID(key, (UUID) value);
        } else if (value.getClass().isEnum()) {
            nbt.setEnum(key, (Enum<?>) value);
        } else {
            @SuppressWarnings("unchecked")
            NBTHandler<Object> handler = (NBTHandler<Object>) proxy.getHandler(value.getClass());
            if (handler != null) {
                handler.set(nbt, key, value);
            } else {
                for (NBTHandler<Object> nbth : proxy.getHandlers()) {
                    if (nbth.fuzzyMatch(value)) {
                        nbth.set(nbt, key, value);
                        return null;
                    }
                }
                throw new IllegalArgumentException("Tried setting an object of type '" + value.getClass().getName()
                        + "'. This is not a supported NBT value. Please check the Wiki for examples!");
            }
        }
        return null;
    }

}
