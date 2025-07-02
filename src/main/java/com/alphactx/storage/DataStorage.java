package com.alphactx.storage;

import com.alphactx.model.PlayerData;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

public interface DataStorage {
    void load(Map<UUID, PlayerData> target) throws SQLException;
    void save(Map<UUID, PlayerData> source) throws SQLException;
    void close() throws SQLException;
}
