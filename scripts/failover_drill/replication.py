from __future__ import annotations

import re
import time

from failover_drill.stack import ComposeStack, StackError

_HOSTNAME = re.compile(r"[a-z0-9][a-z0-9.-]*")

PRIMARY = "multiregion-us"
SECONDARY = "multiregion-eu"
PUBLICATION = "drill_products"
SUBSCRIPTION = "drill_products"


def ensure_primary(stack: ComposeStack, timeout_seconds: float) -> None:
    status = stack.container_status(PRIMARY)
    if status != "running":
        stack.start(PRIMARY)
    stack.wait_postgres(PRIMARY, timeout_seconds)
    stack.wait_postgres(SECONDARY, timeout_seconds)
    wal_level = stack.psql(PRIMARY, "SHOW wal_level;")
    if wal_level != "logical":
        raise StackError(f"postgres-us wal_level is {wal_level}, expected logical")
    superuser = stack.psql(PRIMARY, "SELECT rolsuper FROM pg_roles WHERE rolname = current_user;")
    if superuser != "t":
        raise StackError("logical replication requires the application role to be a superuser")


def reset_subscription(stack: ComposeStack) -> None:
    exists = stack.psql(
        SECONDARY,
        "SELECT count(*) FROM pg_subscription WHERE subname = 'drill_products';",
    )
    if exists != "0":
        stack.psql(
            SECONDARY,
            """
            SET statement_timeout = '20s';
            ALTER SUBSCRIPTION drill_products DISABLE;
            ALTER SUBSCRIPTION drill_products SET (slot_name = NONE);
            DROP SUBSCRIPTION drill_products;
            """,
        )
    stack.psql(
        PRIMARY,
        """
        SELECT pg_drop_replication_slot(slot_name)
        FROM pg_replication_slots
        WHERE slot_name = 'drill_products' AND active = false;
        DROP PUBLICATION IF EXISTS drill_products;
        SELECT pg_catalog.set_writer_mode(true);
        """,
    )
    stack.psql(
        SECONDARY,
        """
        TRUNCATE TABLE products RESTART IDENTITY;
        SELECT pg_catalog.set_writer_mode(false);
        """,
    )


def subscription_sql(host: str) -> str:
    if _HOSTNAME.fullmatch(host) is None:
        raise ValueError(f"replication host {host!r} is not a DNS name")
    return (
        "CREATE SUBSCRIPTION drill_products\n"
        f"  CONNECTION 'host={host} port=5432 dbname=appdb user=appuser password=apppass'\n"
        "  PUBLICATION drill_products\n"
        "  WITH (copy_data = true, create_slot = true, enabled = true, slot_name = drill_products);\n"
    )


def create_subscription(stack: ComposeStack, host: str = "postgres-us") -> None:
    stack.psql(PRIMARY, "CREATE PUBLICATION drill_products FOR TABLE products;")
    stack.psql(SECONDARY, subscription_sql(host))


def wait_until_caught_up(stack: ComposeStack, run_index: int, timeout_seconds: float) -> str:
    token = f"drill-sync-r{run_index}-p1"
    stack.psql(
        PRIMARY,
        f"INSERT INTO products (name, price, region) VALUES ('{token}', 1.00, 'us-east-1');",
    )
    deadline = time.monotonic() + timeout_seconds
    last_state = "subscription has not reported"
    while time.monotonic() < deadline:
        count = stack.psql(
            SECONDARY,
            f"SELECT count(*) FROM products WHERE name = '{token}';",
        )
        if count == "1":
            return token
        last_state = stack.psql(
            SECONDARY,
            """
            SELECT coalesce(subname, 'none') || ' lsn=' || coalesce(latest_end_lsn::text, 'none')
            FROM pg_stat_subscription
            WHERE subname = 'drill_products';
            """,
        ) or "no subscription row"
        time.sleep(0.5)
    raise StackError(
        f"logical replication did not apply {token} within {timeout_seconds}s ({last_state})"
    )


def promote_secondary(stack: ComposeStack) -> None:
    stack.psql(
        SECONDARY,
        """
        SET statement_timeout = '15s';
        ALTER SUBSCRIPTION drill_products DISABLE;
        SELECT setval(
          pg_get_serial_sequence('products', 'id'),
          GREATEST((SELECT COALESCE(MAX(id), 1) FROM products), 1)
        );
        SELECT pg_catalog.set_writer_mode(true);
        """,
    )
    writer = stack.psql(SECONDARY, "SELECT pg_catalog.aurora_is_writer();")
    if writer != "t":
        raise StackError(f"postgres-eu writer mode is {writer}")


def product_counts(stack: ComposeStack, run_index: int) -> dict[str, int]:
    rows = stack.psql(
        SECONDARY,
        f"""
        SELECT name || '|' || count(*)
        FROM products
        WHERE name LIKE 'drill-r{int(run_index)}-%'
        GROUP BY name;
        """,
    )
    counts: dict[str, int] = {}
    if not rows:
        return counts
    for line in rows.splitlines():
        name, count = line.split("|", 1)
        counts[name] = int(count)
    return counts
