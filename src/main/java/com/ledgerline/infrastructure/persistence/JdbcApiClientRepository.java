package com.ledgerline.infrastructure.persistence;

import com.ledgerline.domain.model.ApiClient;
import com.ledgerline.domain.repository.ApiClientRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JdbcApiClientRepository implements ApiClientRepository {

    private final JdbcTemplate jdbc;

    public JdbcApiClientRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ponytail: one indexed lookup per request; cache (e.g. Caffeine, short TTL) if it ever shows up in profiles
    @Override
    public Optional<ApiClient> findActiveByKeyHash(String keyHash) {
        return jdbc.query("""
                SELECT client_id, display_name, role, active FROM api_clients
                 WHERE key_hash = ? AND active AND role <> 'SYSTEM'
                """, (rs, i) -> new ApiClient(rs.getString("client_id"), rs.getString("display_name"),
                ApiClient.Role.valueOf(rs.getString("role")), rs.getBoolean("active")), keyHash).stream().findFirst();
    }

    @Override
    public boolean exists(String clientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM api_clients WHERE client_id = ?)", Boolean.class, clientId));
    }

    @Override
    public void create(ApiClient client, String keyHash) {
        jdbc.update("INSERT INTO api_clients (client_id, display_name, role, key_hash, active) VALUES (?, ?, ?, ?, ?)",
                client.clientId(), client.displayName(), client.role().name(), keyHash, client.active());
    }
}
