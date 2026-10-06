package com.queuelab.core.storage;

import java.util.regex.Pattern;

/**
 * Formato de nombres y referencias. Es una lista blanca estricta: sin separadores, sin {@code ..}, sin
 * rutas absolutas ni caracteres de control, así que no hay nada que normalizar ni escapar.
 */
public final class StorageNames {

    public static final int MAX_LENGTH = 128;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private StorageNames() {
    }

    public static boolean isValid(String name) {
        return name != null && NAME.matcher(name).matches() && !name.contains("..");
    }

    /** Referencia de un nombre en una zona: {@code inputs/<nombre>}. */
    public static String reference(StorageArea area, String name) {
        return area.directory() + "/" + name;
    }
}
