package cn.stellarworld.quicklogin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDescriptorResourcesTest {

    @Test
    void classpathContainsTraditionalPluginDescriptorInsteadOfPaperDescriptor() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        assertNotNull(classLoader.getResource("plugin.yml"), "Expected plugin.yml on the main resource classpath");
        assertNull(classLoader.getResource("paper-plugin.yml"), "paper-plugin.yml should no longer be packaged as a runtime descriptor");

        try (InputStream inputStream = classLoader.getResourceAsStream("plugin.yml")) {
            assertNotNull(inputStream, "Expected plugin.yml content to be readable");
            String pluginYaml = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(pluginYaml.contains("name: StellarQuickLogin"));
            assertTrue(pluginYaml.contains("main: cn.stellarworld.quicklogin.StellarQuickLoginPlugin"));
            assertTrue(pluginYaml.contains("commands:"));
            assertTrue(pluginYaml.contains("aliases: [sqlogin]"));
            assertTrue(pluginYaml.contains("permission: stellarquicklogin.admin"));
            assertFalse(pluginYaml.contains("${version}"), "plugin.yml version placeholder should be expanded during processResources");
        }
    }
}
