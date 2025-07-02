package com.alphactx.storage;

import com.alphactx.model.PlayerData;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.*;
import java.util.Map;
import java.util.UUID;

public class SqliteStorage implements DataStorage {
    private final Connection connection;

    public SqliteStorage(File dataFolder) throws SQLException {
        dataFolder.mkdirs();
        File file = new File(dataFolder, "levelsx.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getPath());
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, data TEXT)");
        }
    }

    @Override
    public void load(Map<UUID, PlayerData> target) throws SQLException {
        try (Statement st = connection.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT uuid,data FROM players");
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString(1));
                String yaml = rs.getString(2);
                YamlConfiguration cfg = new YamlConfiguration();
                try {
                    cfg.loadFromString(yaml);
                } catch (Exception ignored) {}
                PlayerData data = new PlayerData(id);
                DataUtil.load(cfg, data);
                target.put(id, data);
            }
        }
    }

    @Override
    public void save(Map<UUID, PlayerData> source) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("REPLACE INTO players(uuid,data) VALUES(?,?)")) {
            for (PlayerData data : source.values()) {
                YamlConfiguration cfg = new YamlConfiguration();
                DataUtil.save(cfg, data);
                ps.setString(1, data.getUuid().toString());
                ps.setString(2, cfg.saveToString());
                ps.executeUpdate();
            }
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
