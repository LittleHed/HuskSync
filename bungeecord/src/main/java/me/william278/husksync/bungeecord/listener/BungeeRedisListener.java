package me.william278.husksync.bungeecord.listener;

import de.themoep.minedown.MineDown;
import me.william278.husksync.HuskSyncBungeeCord;
import me.william278.husksync.Server;
import me.william278.husksync.util.MessageManager;
import me.william278.husksync.PlayerData;
import me.william278.husksync.Settings;
import me.william278.husksync.migrator.MPDBMigrator;
import me.william278.husksync.redis.RedisListener;
import me.william278.husksync.redis.RedisMessage;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

public class BungeeRedisListener extends RedisListener {

    private static final HuskSyncBungeeCord plugin = HuskSyncBungeeCord.getInstance();

    // Initialize the listener on the bungee
    public BungeeRedisListener() {
        super();
        listen();
    }

    private PlayerData getPlayerCachedData(UUID uuid, String clusterId) {
        PlayerData data = null;
        for (Settings.SynchronisationCluster cluster : Settings.clusters) {
            if (cluster.clusterId().equals(clusterId)) {
                // Get the player data from the cache
                PlayerData cachedData = HuskSyncBungeeCord.dataManager.playerDataCache.get(cluster).getPlayer(uuid);
                if (cachedData != null) {
                    return cachedData;
                }

                data = Objects.requireNonNull(HuskSyncBungeeCord.dataManager.getPlayerData(uuid)).get(cluster); // Get their player data from MySQL
                HuskSyncBungeeCord.dataManager.playerDataCache.get(cluster).updatePlayer(data); // Update the cache
                break;
            }
        }
        return data; // Return the data
    }

    /**
     * Handle an incoming {@link RedisMessage}
     *
     * @param message The {@link RedisMessage} to handle
     */
    @Override
    public void handleMessage(RedisMessage message) {
        // Ignore messages destined for Bukkit servers
        if (message.getMessageTarget().targetServerType() != Settings.ServerType.PROXY) {
            return;
        }
        // Only process redis messages when ready
        if (!HuskSyncBungeeCord.readyForRedis) {
            return;
        }

        switch (message.getMessageType()) {
            case PLAYER_DATA_REQUEST -> ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
                // Get the UUID of the requesting player
                final UUID requestingPlayerUUID = UUID.fromString(message.getMessageData());
                try {
                    // Send the reply, serializing the message data
                    new RedisMessage(RedisMessage.MessageType.PLAYER_DATA_SET,
                            new RedisMessage.MessageTarget(Settings.ServerType.BUKKIT, requestingPlayerUUID, message.getMessageTarget().targetClusterId()),
                            RedisMessage.serialize(getPlayerCachedData(requestingPlayerUUID, message.getMessageTarget().targetClusterId())))
                            .send();

                    // Send an update to all bukkit servers removing the player from the requester cache
                    new RedisMessage(RedisMessage.MessageType.REQUEST_DATA_ON_JOIN,
                            new RedisMessage.MessageTarget(Settings.ServerType.BUKKIT, null, message.getMessageTarget().targetClusterId()),
                            RedisMessage.RequestOnJoinUpdateType.REMOVE_REQUESTER.toString(), requestingPlayerUUID.toString())
                            .send();

                    // Send synchronisation complete message
                    ProxiedPlayer player = ProxyServer.getInstance().getPlayer(requestingPlayerUUID);
                    if (player != null) {
                        player.sendMessage(ChatMessageType.ACTION_BAR, new MineDown(MessageManager.getMessage("synchronisation_complete")).toComponent());
                    }
                } catch (IOException e) {
                    log(Level.SEVERE, "Failed to serialize data when replying to a data request");
                    e.printStackTrace();
                }
            });
            case PLAYER_DATA_UPDATE -> ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
                // Deserialize the PlayerData received
                PlayerData playerData;
                final String serializedPlayerData = message.getMessageData();
                try {
                    playerData = (PlayerData) RedisMessage.deserialize(serializedPlayerData);
                } catch (IOException | ClassNotFoundException e) {
                    log(Level.SEVERE, "Failed to deserialize PlayerData when handling a player update request");
                    e.printStackTrace();
                    return;
                }

                // Update the data in the cache and SQL
                for (Settings.SynchronisationCluster cluster : Settings.clusters) {
                    if (cluster.clusterId().equals(message.getMessageTarget().targetClusterId())) {
                        HuskSyncBungeeCord.dataManager.updatePlayerData(playerData, cluster);
                        break;
                    }
                }

                // Reply with the player data if they are still online (switching server)
                if (Settings.bounceBackSynchronisation) {
                    try {
                        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(playerData.getPlayerUUID());
                        if (player != null) {
                            new RedisMessage(RedisMessage.MessageType.PLAYER_DATA_SET,
                                    new RedisMessage.MessageTarget(Settings.ServerType.BUKKIT, playerData.getPlayerUUID(), message.getMessageTarget().targetClusterId()),
                                    serializedPlayerData)
                                    .send();

                            // Send synchronisation complete message
                            player.sendMessage(ChatMessageType.ACTION_BAR, new MineDown(MessageManager.getMessage("synchronisation_complete")).toComponent());
                        }
                    } catch (IOException e) {
                        log(Level.SEVERE, "Failed to re-serialize PlayerData when handling a player update request");
                        e.printStackTrace();
                    }
                }
            });
            case CONNECTION_HANDSHAKE -> {
                // Reply to a Bukkit server's connection handshake to complete the process
                if (HuskSyncBungeeCord.isDisabling) return; // Return if the Proxy is disabling
                final UUID serverUUID = UUID.fromString(message.getMessageDataElements()[0]);
                final boolean hasMySqlPlayerDataBridge = Boolean.parseBoolean(message.getMessageDataElements()[1]);
                final String bukkitBrand = message.getMessageDataElements()[2];
                final String huskSyncVersion = message.getMessageDataElements()[3];
                try {
                    new RedisMessage(RedisMessage.MessageType.CONNECTION_HANDSHAKE,
                            new RedisMessage.MessageTarget(Settings.ServerType.BUKKIT, null, message.getMessageTarget().targetClusterId()),
                            serverUUID.toString(), plugin.getProxy().getName())
                            .send();
                    HuskSyncBungeeCord.synchronisedServers.add(
                            new Server(serverUUID, hasMySqlPlayerDataBridge,
                                    huskSyncVersion, bukkitBrand, message.getMessageTarget().targetClusterId()));
                    log(Level.INFO, "Completed handshake with " + bukkitBrand + " server (" + serverUUID + ")");
                } catch (IOException e) {
                    log(Level.SEVERE, "Failed to serialize handshake message data");
                    e.printStackTrace();
                }
            }
            case TERMINATE_HANDSHAKE -> {
                // Terminate the handshake with a Bukkit server
                final UUID serverUUID = UUID.fromString(message.getMessageDataElements()[0]);
                final String bukkitBrand = message.getMessageDataElements()[1];

                // Remove a server from the synchronised server list
                Server serverToRemove = null;
                for (Server server : HuskSyncBungeeCord.synchronisedServers) {
                    if (server.serverUUID().equals(serverUUID)) {
                        serverToRemove = server;
                        break;
                    }
                }
                HuskSyncBungeeCord.synchronisedServers.remove(serverToRemove);
                log(Level.INFO, "Terminated the handshake with " + bukkitBrand + " server (" + serverUUID + ")");
            }
            case DECODED_MPDB_DATA_SET -> {
                // Deserialize the PlayerData received
                PlayerData playerData;
                final String serializedPlayerData = message.getMessageDataElements()[0];
                final String playerName = message.getMessageDataElements()[1];
                try {
                    playerData = (PlayerData) RedisMessage.deserialize(serializedPlayerData);
                } catch (IOException | ClassNotFoundException e) {
                    log(Level.SEVERE, "Failed to deserialize PlayerData when handling incoming decoded MPDB data");
                    e.printStackTrace();
                    return;
                }

                // Get the migrator
                MPDBMigrator migrator = HuskSyncBungeeCord.mpdbMigrator;

                // Add the incoming data to the data to be saved
                migrator.incomingPlayerData.put(playerData, playerName);

                // Increment players migrated
                migrator.playersMigrated++;
                plugin.getBungeeLogger().log(Level.INFO, "Migrated " + migrator.playersMigrated + "/" + migrator.migratedDataSent + " players.");

                // When all the data has been received, save it
                if (migrator.migratedDataSent == migrator.playersMigrated) {
                    ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> migrator.loadIncomingData(migrator.incomingPlayerData,
                            HuskSyncBungeeCord.dataManager));
                }
            }
            case API_DATA_REQUEST -> ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
                final UUID playerUUID = UUID.fromString(message.getMessageDataElements()[0]);
                final UUID requestUUID = UUID.fromString(message.getMessageDataElements()[1]);
                try {
                    final PlayerData data = getPlayerCachedData(playerUUID, message.getMessageTarget().targetClusterId());

                    if (data == null) {
                        new RedisMessage(RedisMessage.MessageType.API_DATA_CANCEL,
                                new RedisMessage.MessageTarget(Settings.ServerType.BUKKIT, null, message.getMessageTarget().targetClusterId()),
                                requestUUID.toString())
                                .send();
                    } else {
                        // Send the reply alongside the request UUID, serializing the requested message data
                        new RedisMessage(RedisMessage.MessageType.API_DATA_RETURN,
                                new RedisMessage.MessageTarget(Settings.ServerType.BUKKIT, null, message.getMessageTarget().targetClusterId()),
                                requestUUID.toString(),
                                RedisMessage.serialize(data))
                                .send();
                    }
                } catch (IOException e) {
                    plugin.getBungeeLogger().log(Level.SEVERE, "Failed to serialize PlayerData requested via the API");
                }
            });
        }
    }

    /**
     * Log to console
     *
     * @param level   The {@link Level} to log
     * @param message Message to log
     */
    @Override
    public void log(Level level, String message) {
        plugin.getBungeeLogger().log(level, message);
    }
}