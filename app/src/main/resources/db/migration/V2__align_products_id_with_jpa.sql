-- V2: align products.id with the Product JPA entity.
--
-- V1 keeps the legacy SERIAL (int4) type verbatim so that baseline-on-migrate
-- stays honest for pre-existing volumes. The Product entity declares Long +
-- IDENTITY, which Hibernate validate maps to bigint; this migration converges
-- both fresh (V1-applied) and baselined (legacy-init) databases to that type.
-- The SERIAL sequence is intentionally left untouched: nextval() already
-- returns bigint values, which the widened column accepts.
ALTER TABLE products ALTER COLUMN id TYPE BIGINT;
