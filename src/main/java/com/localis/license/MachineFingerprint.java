package com.localis.license;

import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/** Identificador estable de la maquina, calculado a partir de las MAC fisicas. Se envia al proveedor para licenciar. */
public final class MachineFingerprint {

    private MachineFingerprint() {}

    public static String current() {
        List<String> macs = new ArrayList<>();
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length == 6 && !ni.isVirtual() && !ni.isLoopback()) {
                    macs.add(HexFormat.of().formatHex(mac));
                }
            }
            Collections.sort(macs);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join(",", macs).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular el identificador de la maquina", e);
        }
    }
}
