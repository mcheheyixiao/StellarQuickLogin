package cn.stellarworld.quicklogin.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StellarQuickLoginConfigTest {

    @Test
    void enabledDirectCheckDefaultsToTrueWhenUnset() {
        StellarQuickLoginConfig config = StellarQuickLoginConfig.from(new YamlConfiguration());

        assertTrue(config.website().enabledDirectCheck());
    }
}
