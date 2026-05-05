package com.xkstudios.crowns.api;

import java.util.List;

public record ModuleHealth(
        ModuleDescriptor descriptor,
        ServiceState state,
        String summary,
        List<String> warnings,
        long checkedAt
) {
    public ModuleHealth {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ModuleHealth of(ModuleDescriptor descriptor, ServiceState state, String summary, List<String> warnings) {
        return new ModuleHealth(descriptor, state, summary == null ? "" : summary, warnings, System.currentTimeMillis());
    }

    public static ModuleHealth ready(ModuleDescriptor descriptor, String summary) {
        return of(descriptor, ServiceState.READY, summary, List.of());
    }

    public static ModuleHealth degraded(ModuleDescriptor descriptor, String summary, List<String> warnings) {
        return of(descriptor, ServiceState.DEGRADED, summary, warnings);
    }
}
