package com.localis.license;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Contenido de un fichero de licencia. 'expiresAt' null = licencia vitalicia. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record License(String customer, String issuedAt, String expiresAt, int maxMachines, List<String> machineIds,
        List<String> modules, String signature) {

    public License {
        machineIds = machineIds == null ? List.of() : List.copyOf(machineIds);
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    /** Texto canonico que se firma; cualquier cambio en la licencia invalida la firma. */
    public String signedPayload() {
        List<String> ids = new ArrayList<>(machineIds);
        List<String> mods = new ArrayList<>(modules);
        Collections.sort(ids);
        Collections.sort(mods);
        return String.join("\n", "localis-license-v1", String.valueOf(customer), String.valueOf(issuedAt),
                expiresAt == null ? "perpetual" : expiresAt, String.valueOf(maxMachines), String.join(",", ids),
                String.join(",", mods));
    }
}
