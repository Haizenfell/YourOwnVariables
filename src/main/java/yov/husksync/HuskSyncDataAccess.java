package yov.husksync;

import net.william278.husksync.api.HuskSyncAPI;
import net.william278.husksync.data.Data;
import net.william278.husksync.data.DataHolder;
import net.william278.husksync.data.Identifier;

import java.lang.reflect.Method;
import java.util.Optional;

public class HuskSyncDataAccess {

    public static YovVariablesData readYovData(HuskSyncAPI api, Object holder, Identifier id) {
        if (holder == null || id == null) return null;

        try {
            if (holder instanceof DataHolder dh) {
                Optional<? extends Data> opt = dh.getData(id);
                if (opt.isPresent() && opt.get() instanceof YovVariablesData y) return y;
                Data direct = dh.getData().get(id);
                if (direct instanceof YovVariablesData y) return y;
            }
        } catch (Throwable ignored) {
        }

        Object direct = invokeBest(holder, new String[]{"getData", "getCustomData"}, new Class[]{Identifier.class}, new Object[]{id});
        YovVariablesData got = unwrapYov(direct);
        if (got != null) return got;

        Object store = invokeBest(holder, new String[]{"getCustomDataStore", "getCustomData"}, new Class[]{}, new Object[]{});
        if (store != null) {
            Object r = invokeBest(store, new String[]{"get", "getData", "getCustomData"}, new Class[]{Identifier.class}, new Object[]{id});
            got = unwrapYov(r);
            if (got != null) return got;

            if (r instanceof String s && api != null) {
                try {
                    return api.deserializeData(s, YovVariablesData.class);
                } catch (Exception ignored) {
                }
            }
        }

        Object old = invokeBest(holder, new String[]{"getData", "getCustomData"}, new Class[]{Identifier.class, Class.class}, new Object[]{id, YovVariablesData.class});
        got = unwrapYov(old);
        if (got != null) return got;

        return null;
    }

    public static void writeYovData(HuskSyncAPI api, Object holder, Identifier id, YovVariablesData data) {
        if (holder == null || id == null || data == null) return;

        try {
            if (holder instanceof DataHolder dh) {
                dh.setData(id, data);
                return;
            }
        } catch (Throwable ignored) {
        }

        Object ok = invokeBest(holder, new String[]{"setData", "setCustomData", "putData", "putCustomData"}, new Class[]{Identifier.class, Object.class}, new Object[]{id, data});
        if (ok != null) return;

        if (api != null) {
            Object ok2 = invokeBest(holder, new String[]{"setData", "setCustomData", "putData", "putCustomData"}, new Class[]{Identifier.class, String.class}, new Object[]{id, safeSerialize(api, data)});
            if (ok2 != null) return;
        }

        Object store = invokeBest(holder, new String[]{"getCustomDataStore", "getCustomData"}, new Class[]{}, new Object[]{});
        if (store != null) {
            Object r1 = invokeBest(store, new String[]{"set", "setData", "put", "putData"}, new Class[]{Identifier.class, Object.class}, new Object[]{id, data});
            if (r1 != null) return;

            if (api != null) {
                invokeBest(store, new String[]{"set", "setData", "put", "putData"}, new Class[]{Identifier.class, String.class}, new Object[]{id, safeSerialize(api, data)});
            }
        }
    }

    private static String safeSerialize(HuskSyncAPI api, YovVariablesData data) {
        try {
            return api.serializeData(data);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static YovVariablesData unwrapYov(Object obj) {
        if (obj == null) return null;
        if (obj instanceof YovVariablesData d) return d;
        if (obj instanceof Optional<?> opt) {
            Object v = opt.orElse(null);
            if (v instanceof YovVariablesData d) return d;
        }
        return null;
    }

    private static Object invokeBest(Object target, String[] names, Class<?>[] paramTypes, Object[] args) {
        for (String name : names) {
            try {
                Method m = findMethod(target.getClass(), name, paramTypes);
                if (m == null) continue;
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        try {
            return cls.getMethod(name, paramTypes);
        } catch (Exception ignored) {
        }
        try {
            return cls.getDeclaredMethod(name, paramTypes);
        } catch (Exception ignored) {
        }
        for (Method m : cls.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterCount() != paramTypes.length) continue;
            boolean ok = true;
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (paramTypes[i] == Object.class) continue;
                if (!ps[i].isAssignableFrom(paramTypes[i]) && !paramTypes[i].isAssignableFrom(ps[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) return m;
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterCount() != paramTypes.length) continue;
            return m;
        }
        return null;
    }
}