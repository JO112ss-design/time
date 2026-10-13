package com.timerewind;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/** config/timerewind-common.toml */
public final class Config {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.DoubleValue REWIND_SECONDS;
    public static final ForgeConfigSpec.DoubleValue COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.DoubleValue MIN_DAMAGE;
    public static final ForgeConfigSpec.BooleanValue LETHAL_ONLY;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_DAMAGE_TYPES;

    public static final ForgeConfigSpec.BooleanValue REWIND_INVENTORY;
    public static final ForgeConfigSpec.BooleanValue REWIND_BLOCKS;
    public static final ForgeConfigSpec.BooleanValue REWIND_ENTITIES;
    public static final ForgeConfigSpec.IntValue WORLD_RADIUS;
    public static final ForgeConfigSpec.IntValue MAX_BLOCK_CHANGES;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Trigger settings").push("trigger");
        ENABLED = b.comment("Master switch.")
                .define("enabled", true);
        REWIND_SECONDS = b.comment("How many seconds to go back when damaged.")
                .defineInRange("rewindSeconds", 3.0, 0.5, 30.0);
        COOLDOWN_SECONDS = b.comment("Cooldown after a rewind. Damage taken during the cooldown is applied normally.",
                        "Values shorter than rewindSeconds are raised to rewindSeconds.")
                .defineInRange("cooldownSeconds", 3.0, 0.0, 600.0);
        MIN_DAMAGE = b.comment("Minimum final damage (after armor) that triggers a rewind.")
                .defineInRange("minDamage", 0.0, 0.0, 1000.0);
        LETHAL_ONLY = b.comment("If true, only damage that would kill the player triggers a rewind.")
                .define("lethalOnly", false);
        EXCLUDED_DAMAGE_TYPES = b.comment("Damage type IDs that never trigger a rewind.")
                .defineList("excludedDamageTypes", List.of("minecraft:generic_kill"), o -> o instanceof String);
        b.pop();

        b.comment("What gets rewound").push("rewind");
        REWIND_INVENTORY = b.comment("Rewind the player's inventory and XP (prevents item duplication).")
                .define("inventory", true);
        REWIND_BLOCKS = b.comment("Rewind block changes around the player.")
                .define("blocks", true);
        REWIND_ENTITIES = b.comment("Rewind mobs, items and other entities around the player.")
                .define("entities", true);
        WORLD_RADIUS = b.comment("Radius (blocks) around the player in which blocks/entities are recorded and rewound.")
                .defineInRange("radius", 16, 2, 64);
        MAX_BLOCK_CHANGES = b.comment("Safety cap on recorded block changes per dimension.")
                .defineInRange("maxBlockChanges", 200000, 1000, 5000000);
        b.pop();

        SPEC = b.build();
    }

    private Config() {}
}
