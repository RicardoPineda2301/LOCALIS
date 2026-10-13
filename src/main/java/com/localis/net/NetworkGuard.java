package com.localis.net;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Garantiza el funcionamiento sin internet: toda conexion saliente debe pasar por aqui.
 * Solo se permiten los hosts configurados (por defecto, unicamente loopback).
 */
public final class NetworkGuard {

    private final List<String> allowedHosts;

    public NetworkGuard(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts.stream().map(h -> normalize(h)).toList();
    }

    public void check(URI uri) {
        String host = uri.getHost();
        if (host == null || !allowedHosts.contains(normalize(host))) {
            throw new SecurityException("Conexion bloqueada por el modo local: host '" + host
                    + "' no esta en network.allowedHosts");
        }
    }

    private static String normalize(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h;
    }
}
