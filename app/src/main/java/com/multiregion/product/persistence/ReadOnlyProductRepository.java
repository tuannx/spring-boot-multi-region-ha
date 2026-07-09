package com.multiregion.product.persistence;

import com.multiregion.product.domain.Product;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;

/**
 * Read-only repository that queries the ReadPool directly.
 * Bypasses the RoutingDataSource for guaranteed read-replica access.
 */
@Repository
public class ReadOnlyProductRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReadOnlyProductRepository(@Qualifier("readDataSource") DataSource readDataSource) {
        this.jdbcTemplate = new JdbcTemplate(readDataSource);
    }

    public List<Product> findAll() {
        return jdbcTemplate.query(
            "SELECT id, name, price, region, created_at AS createdAt, updated_at AS updatedAt FROM products ORDER BY id",
            (rs, rowNum) -> {
                Product p = new Product();
                p.setId(rs.getLong("id"));
                p.setName(rs.getString("name"));
                p.setPrice(rs.getBigDecimal("price"));
                p.setRegion(rs.getString("region"));
                if (rs.getTimestamp("createdAt") != null)
                    p.setCreatedAt(rs.getTimestamp("createdAt").toLocalDateTime());
                if (rs.getTimestamp("updatedAt") != null)
                    p.setUpdatedAt(rs.getTimestamp("updatedAt").toLocalDateTime());
                return p;
            }
        );
    }
}
