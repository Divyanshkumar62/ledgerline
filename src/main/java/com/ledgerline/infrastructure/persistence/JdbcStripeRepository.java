package com.ledgerline.infrastructure.persistence;

import com.ledgerline.domain.model.AccountType;
import com.ledgerline.domain.model.StripeLayout;
import com.ledgerline.domain.repository.StripeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class JdbcStripeRepository implements StripeRepository {

    private final JdbcTemplate jdbc;

    public JdbcStripeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Passed as a text array literal and cast server-side, so any number of ids is one bind parameter. */
    static String uuidArray(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    private record Row(UUID id, AccountType type, boolean frozen, UUID parentId, UUID stripeId) {
    }

    @Override
    public Map<UUID, StripeLayout> findLayouts(Collection<UUID> accountIds) {
        // One row per (parent, stripe), in stripe order. For a stripe the join finds nothing, so stripe_id is null.
        List<Row> rows = jdbc.query("""
                SELECT a.id, a.type, a.frozen, a.parent_account_id, s.id AS stripe_id
                  FROM accounts a
                  LEFT JOIN accounts s ON s.parent_account_id = a.id
                 WHERE a.id = ANY (?::uuid[])
                   AND (a.stripe_count > 0 OR a.parent_account_id IS NOT NULL)
                 ORDER BY a.id, s.stripe_index
                """, (rs, i) -> new Row(rs.getObject("id", UUID.class), AccountType.valueOf(rs.getString("type")),
                rs.getBoolean("frozen"), rs.getObject("parent_account_id", UUID.class), rs.getObject("stripe_id", UUID.class)),
                uuidArray(accountIds));

        Map<UUID, StripeLayout> layouts = new LinkedHashMap<>();
        rows.stream().collect(Collectors.groupingBy(Row::id, LinkedHashMap::new, Collectors.toList()))
                .forEach((id, group) -> {
                    Row head = group.get(0);
                    List<UUID> stripeIds = group.stream().map(Row::stripeId).filter(Objects::nonNull).toList();
                    layouts.put(id, new StripeLayout(id, head.type(), head.frozen(), head.parentId(), stripeIds));
                });
        return layouts;
    }

    @Override
    public List<UUID> findStripeIds(UUID parentAccountId) {
        return jdbc.queryForList("SELECT id FROM accounts WHERE parent_account_id = ? ORDER BY stripe_index",
                UUID.class, parentAccountId);
    }

    @Override
    public long sumStripeBalances(UUID parentAccountId) {
        Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE parent_account_id = ?",
                Long.class, parentAccountId);
        return sum == null ? 0 : sum;
    }
}
