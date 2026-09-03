"""Add isolated advertising VPN servers and the splash placement.

Revision ID: 20260829_0002
Revises: 20260824_0001
"""

from datetime import UTC, datetime

import sqlalchemy as sa
from alembic import op


revision = "20260829_0002"
down_revision = "20260824_0001"
branch_labels = None
depends_on = None


def _table_names() -> set[str]:
    return set(sa.inspect(op.get_bind()).get_table_names())


def upgrade() -> None:
    # The guard matters for installations that previously invoked
    # Base.metadata.create_all() before Alembic was introduced.
    if "ad_vpn_servers" not in _table_names():
        op.create_table(
            "ad_vpn_servers",
            sa.Column("id", sa.Integer(), nullable=False),
            sa.Column("public_id", sa.String(length=80), nullable=False),
            sa.Column("config_encrypted", sa.Text(), nullable=False),
            sa.Column("protocol", sa.String(length=32), nullable=False),
            sa.Column("tag", sa.String(length=160), nullable=False),
            sa.Column("host", sa.String(length=255), nullable=False),
            sa.Column("port", sa.Integer(), nullable=True),
            sa.Column("resolved_ip", sa.String(length=64), nullable=False),
            sa.Column("country_code", sa.String(length=8), nullable=False),
            sa.Column("country_name", sa.String(length=100), nullable=False),
            sa.Column("priority", sa.Integer(), nullable=False),
            sa.Column("enabled", sa.Boolean(), nullable=False),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.Column("updated_at", sa.DateTime(), nullable=False),
            sa.Column("resolved_at", sa.DateTime(), nullable=True),
            sa.PrimaryKeyConstraint("id"),
        )
        op.create_index(
            "ix_ad_vpn_servers_enabled", "ad_vpn_servers", ["enabled"], unique=False
        )
        op.create_index(
            "ix_ad_vpn_servers_public_id",
            "ad_vpn_servers",
            ["public_id"],
            unique=True,
        )

    if "ad_placements" in _table_names():
        # Portable, repeatable seed: running the upgrade logic again does not
        # create a duplicate placement.
        statement = sa.text(
                """
                INSERT INTO ad_placements
                    (key, enabled, ad_format, unit_id, every_n_actions,
                     cooldown_seconds, timeout_ms, max_per_day, updated_at)
                SELECT
                    :key, :enabled, :ad_format, :unit_id, :every_n_actions,
                    :cooldown_seconds, :timeout_ms, :max_per_day, :updated_at
                WHERE NOT EXISTS (
                    SELECT 1 FROM ad_placements WHERE key = :key
                )
                """
            ).bindparams(
                sa.bindparam("key", type_=sa.String(32)),
                sa.bindparam("enabled", type_=sa.Boolean()),
                sa.bindparam("ad_format", type_=sa.String(32)),
                sa.bindparam("unit_id", type_=sa.String(180)),
                sa.bindparam("every_n_actions", type_=sa.Integer()),
                sa.bindparam("cooldown_seconds", type_=sa.Integer()),
                sa.bindparam("timeout_ms", type_=sa.Integer()),
                sa.bindparam("max_per_day", type_=sa.Integer()),
                sa.bindparam("updated_at", type_=sa.DateTime()),
            )
        op.get_bind().execute(
            statement,
            {
                "key": "splash",
                "enabled": False,
                "ad_format": "app_open",
                "unit_id": "",
                "every_n_actions": 1,
                "cooldown_seconds": 0,
                "timeout_ms": 12000,
                "max_per_day": 0,
                "updated_at": datetime.now(UTC).replace(tzinfo=None),
            },
        )


def downgrade() -> None:
    tables = _table_names()
    if "ad_placements" in tables:
        op.get_bind().execute(
            sa.text("DELETE FROM ad_placements WHERE key = :key"),
            {"key": "splash"},
        )
    if "ad_vpn_servers" in tables:
        indexes = {
            item["name"]
            for item in sa.inspect(op.get_bind()).get_indexes("ad_vpn_servers")
        }
        if "ix_ad_vpn_servers_public_id" in indexes:
            op.drop_index(
                "ix_ad_vpn_servers_public_id", table_name="ad_vpn_servers"
            )
        if "ix_ad_vpn_servers_enabled" in indexes:
            op.drop_index("ix_ad_vpn_servers_enabled", table_name="ad_vpn_servers")
        op.drop_table("ad_vpn_servers")
