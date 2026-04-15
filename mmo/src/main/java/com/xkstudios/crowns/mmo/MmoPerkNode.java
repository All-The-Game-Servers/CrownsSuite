package com.xkstudios.crowns.mmo;

public record MmoPerkNode(
        String key,
        MmoSkill skill,
        String displayName,
        int requiredLevel,
        String description
) {
}
