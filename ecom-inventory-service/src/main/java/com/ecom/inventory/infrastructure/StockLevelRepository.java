package com.ecom.inventory.infrastructure;

import com.ecom.inventory.domain.StockLevel;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class StockLevelRepository {

    private static final String COLUMNS = "sku, warehouse_id, on_hand, reserved, version, updated_at";
    private static final RowMapper<StockLevel> MAPPER = StockLevelRepository::map;

    private final JdbcTemplate jdbc;

    public StockLevelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the existing rows for {@code skus} in SKU order (Postgres applies the row locks above the
     * sort, so concurrent callers always acquire them in the same order). Unknown SKUs are absent.
     */
    public Map<String, StockLevel> lockForUpdate(Collection<String> skus) {
        return toMap(jdbc.query(
                con -> {
                    var ps = con.prepareStatement("SELECT " + COLUMNS + " FROM stock_levels"
                            + " WHERE warehouse_id = ? AND sku = ANY (?) ORDER BY sku FOR UPDATE");
                    ps.setString(1, StockLevel.DEFAULT_WAREHOUSE);
                    ps.setArray(2, textArray(con, skus));
                    return ps;
                },
                MAPPER));
    }

    public Map<String, StockLevel> findAll(Collection<String> skus) {
        return toMap(jdbc.query(
                con -> {
                    var ps = con.prepareStatement(
                            "SELECT " + COLUMNS + " FROM stock_levels WHERE warehouse_id = ? AND sku = ANY (?)");
                    ps.setString(1, StockLevel.DEFAULT_WAREHOUSE);
                    ps.setArray(2, textArray(con, skus));
                    return ps;
                },
                MAPPER));
    }

    public Optional<StockLevel> find(String sku) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM stock_levels WHERE sku = ? AND warehouse_id = ?",
                        MAPPER, sku, StockLevel.DEFAULT_WAREHOUSE)
                .stream()
                .findFirst();
    }

    /** @return true if the row was created by this call. */
    public boolean insertIfAbsent(String sku) {
        return jdbc.update(
                        "INSERT INTO stock_levels (sku, warehouse_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                        sku, StockLevel.DEFAULT_WAREHOUSE)
                == 1;
    }

    /** Applies deltas and bumps the row version; the table CHECKs reject any over-reservation. */
    public StockLevel adjust(String sku, int onHandDelta, int reservedDelta) {
        return jdbc.queryForObject(
                "UPDATE stock_levels SET on_hand = on_hand + ?, reserved = reserved + ?, version = version + 1,"
                        + " updated_at = now() WHERE sku = ? AND warehouse_id = ? RETURNING " + COLUMNS,
                MAPPER, onHandDelta, reservedDelta, sku, StockLevel.DEFAULT_WAREHOUSE);
    }

    public StockLevel setOnHand(String sku, int onHand) {
        return jdbc.queryForObject(
                "UPDATE stock_levels SET on_hand = ?, version = version + 1, updated_at = now()"
                        + " WHERE sku = ? AND warehouse_id = ? RETURNING " + COLUMNS,
                MAPPER, onHand, sku, StockLevel.DEFAULT_WAREHOUSE);
    }

    private static Array textArray(java.sql.Connection con, Collection<String> values) throws SQLException {
        return con.createArrayOf("text", values.toArray());
    }

    private static Map<String, StockLevel> toMap(List<StockLevel> rows) {
        Map<String, StockLevel> map = new LinkedHashMap<>();
        rows.forEach(r -> map.put(r.sku(), r));
        return map;
    }

    private static StockLevel map(ResultSet rs, int i) throws SQLException {
        return new StockLevel(
                rs.getString("sku"),
                rs.getString("warehouse_id"),
                rs.getInt("on_hand"),
                rs.getInt("reserved"),
                rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
