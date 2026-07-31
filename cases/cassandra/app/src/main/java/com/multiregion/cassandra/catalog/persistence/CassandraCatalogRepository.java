package com.multiregion.cassandra.catalog.persistence;

import org.springframework.data.cassandra.repository.CassandraRepository;

import java.util.UUID;

interface CassandraCatalogRepository extends CassandraRepository<CassandraCatalogRow, UUID> {
}
