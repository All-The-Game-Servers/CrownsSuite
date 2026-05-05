package com.xkstudios.crowns.api;

import java.util.List;

public record ModuleDescriptor(
        String key,
        String displayName,
        String pluginName,
        String version,
        String requiredApiVersion,
        List<String> requiredDependencies,
        List<String> optionalDependencies,
        List<String> providedServices
) {
    public ModuleDescriptor {
        requiredDependencies = requiredDependencies == null ? List.of() : List.copyOf(requiredDependencies);
        optionalDependencies = optionalDependencies == null ? List.of() : List.copyOf(optionalDependencies);
        providedServices = providedServices == null ? List.of() : List.copyOf(providedServices);
    }
}
