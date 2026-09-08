package com.bashworks.ugs.camera;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class PluginInfo {
    public static final String VERSION = readVersion();
    public static final String TITLE = "G-BASHWARE CamerAim " + VERSION;
    private PluginInfo() {}

    private static String readVersion() {
        Properties properties = new Properties();
        try (InputStream input = PluginInfo.class.getResourceAsStream("/cameraim-version.properties")) {
            if (input != null) properties.load(input);
        } catch (IOException ignored) { }
        return properties.getProperty("version", "development");
    }
}
