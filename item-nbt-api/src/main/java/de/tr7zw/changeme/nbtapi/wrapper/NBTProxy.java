package de.tr7zw.changeme.nbtapi.wrapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import de.tr7zw.changeme.nbtapi.iface.NBTHandler;

public interface NBTProxy {

    static final Map<Class<?>, NBTHandler<Object>> handlers = new HashMap<>();

    public default void init() {

    }

    @SuppressWarnings("unchecked")
    public default <T> NBTHandler<T> getHandler(Class<T> clazz) {
        return (NBTHandler<T>) handlers.get(clazz);
    }

    public default Collection<NBTHandler<Object>> getHandlers() {
        return handlers.values();
    }

    public default <T> void registerHandler(Class<T> clazz, NBTHandler<T> handler) {
        handlers.put(clazz, (NBTHandler<Object>) handler);
    }

}
