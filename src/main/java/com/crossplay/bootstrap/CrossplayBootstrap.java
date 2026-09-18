package com.crossplay.bootstrap;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

public final class CrossplayBootstrap extends JavaPlugin implements Listener {
    private static final String PREFIX = "[CrossplaySetup] ";
    private File doneFile;
    private File geyserConfig;
    private File floodgateKey;
    private File geyserKey;
    private File serverProperties;

    @Override
    public void onEnable() {
        this.doneFile = new File(this.getDataFolder(), ".done");
        this.geyserConfig = new File(this.getDataFolder().getParentFile(), "Geyser-Spigot/config.yml");
        this.floodgateKey = new File(this.getDataFolder().getParentFile(), "floodgate/key.pem");
        this.geyserKey = new File(this.getDataFolder().getParentFile(), "Geyser-Spigot/key.pem");
        this.serverProperties = new File(this.getServer().getWorldContainer(), "server.properties");

        if (this.doneFile.exists()) {
            this.getLogger().info(PREFIX + "Bootstrap already completed. Skipping setup.");
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        this.getLogger().info(PREFIX + "Waiting for server to finish loading to start Crossplay Setup...");
    }

    @Override
    public void onDisable() {
        this.getLogger().info(PREFIX + "Plugin disabled.");
    }

    @EventHandler
    public void onServerLoaded(ServerLoadEvent event) {
        // Run setup 100 ticks (5 seconds) after server fully loads to ensure other plugins are initialized
        Bukkit.getScheduler().runTaskLater(this, this::runBootstrap, 100L);
    }

    private void runBootstrap() {
        this.getLogger().info(PREFIX + "Starting first-time crossplay bootstrap...");

        // Check if Geyser and Floodgate exist
        Plugin geyserPlugin = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        Plugin floodgatePlugin = Bukkit.getPluginManager().getPlugin("floodgate");

        if (geyserPlugin == null || floodgatePlugin == null) {
            this.getLogger().severe(PREFIX + "Geyser-Spigot or floodgate plugin not found! Aborting bootstrap.");
            return;
        }

        if (!this.geyserConfig.exists() || !this.floodgateKey.exists() || !this.serverProperties.exists()) {
            this.getLogger().severe(PREFIX + "Missing required configuration files! Aborting bootstrap.");
            return;
        }

        try {
            // 1. Get Java server port from server.properties
            int serverPort = 25565;
            Properties properties = new Properties();
            try (FileInputStream input = new FileInputStream(this.serverProperties)) {
                properties.load(input);
            }
            String portValue = properties.getProperty("server-port");
            if (portValue != null) {
                serverPort = Integer.parseInt(portValue.trim());
            }

            // 2. Modify Geyser config using line-by-line replacement to PRESERVE COMMENTS
            boolean modifiedGeyser = modifyGeyserConfig(serverPort);
            if (!modifiedGeyser) {
                this.getLogger().warning(PREFIX + "Failed to modify Geyser config (Keys not found?).");
            } else {
                this.getLogger().info(PREFIX + "Configured Geyser config.yml successfully (preserved comments).");
            }

            // 3. Copy Floodgate key to Geyser
            Files.copy(this.floodgateKey.toPath(), this.geyserKey.toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.getLogger().info(PREFIX + "Copied Floodgate key.pem to Geyser-Spigot.");

            // 4. Create .done file
            if (!this.getDataFolder().exists()) {
                this.getDataFolder().mkdirs();
            }
            if (!this.doneFile.exists()) {
                this.doneFile.createNewFile();
            }

            this.getLogger().info(PREFIX + "Crossplay Bootstrap completed successfully!");

            // 5. Restart sequence
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Bukkit.broadcastMessage("");
                Bukkit.broadcastMessage("§b======================================");
                Bukkit.broadcastMessage("§3Crossplay Setup Complete");
                Bukkit.broadcastMessage("§fThe server will now restart once to apply changes.");
                Bukkit.broadcastMessage("§aNo further setup will be required.");
                Bukkit.broadcastMessage("§b======================================");
                Bukkit.broadcastMessage("");
                
                // Use shutdown() instead of spigot().restart() since Pterodactyl handles restarts automatically
                // and spigot().restart() requires restart-script in spigot.yml which is often misconfigured.
                Bukkit.shutdown();
            }, 100L);

        } catch (Exception ex) {
            this.getLogger().log(Level.SEVERE, PREFIX + "Bootstrap encountered a fatal error.", ex);
        }
    }

    private boolean modifyGeyserConfig(int serverPort) throws IOException {
        List<String> lines = Files.readAllLines(this.geyserConfig.toPath());
        List<String> newLines = new ArrayList<>();
        boolean modified = false;

        boolean insideBedrock = false;
        boolean insideServer = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.equals("bedrock:")) {
                insideBedrock = true;
                insideServer = false;
            } else if (trimmed.equals("server:")) {
                insideServer = true;
                insideBedrock = false;
            } else if (!line.startsWith(" ") && !line.startsWith("\t") && !trimmed.startsWith("#") && !trimmed.isEmpty()) {
                // Not indented, we exited the section
                insideBedrock = false;
                insideServer = false;
            }

            // Replace values
            if (insideBedrock && trimmed.startsWith("port:")) {
                line = line.replaceFirst("port:.*", "port: " + serverPort);
                modified = true;
            } else if (insideBedrock && trimmed.startsWith("clone-remote-port:")) {
                line = line.replaceFirst("clone-remote-port:.*", "clone-remote-port: true");
                modified = true;
            } else if (insideServer && trimmed.startsWith("auth-type:")) {
                line = line.replaceFirst("auth-type:.*", "auth-type: floodgate");
                modified = true;
            }

            newLines.add(line);
        }

        if (modified) {
            Files.write(this.geyserConfig.toPath(), newLines);
        }
        return modified;
    }
}
