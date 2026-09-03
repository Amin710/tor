"""Add persistent device enrollment state for secure bootstrap v2.

Revision ID: 20260830_0003
Revises: 20260829_0002
"""

import sqlalchemy as sa
from alembic import op


revision = "20260830_0003"
down_revision = "20260829_0002"
branch_labels = None
depends_on = None


def _column_names() -> set[str]:
    inspector = sa.inspect(op.get_bind())
    return {item["name"] for item in inspector.get_columns("installations")}


def _index_names() -> set[str]:
    inspector = sa.inspect(op.get_bind())
    return {item["name"] for item in inspector.get_indexes("installations")}


def upgrade() -> None:
    columns = _column_names()
    with op.batch_alter_table("installations") as batch:
        if "signing_public_key_b64" not in columns:
            batch.add_column(
                sa.Column(
                    "signing_public_key_b64",
                    sa.Text(),
                    nullable=False,
                    server_default="",
                )
            )
        if "credential_hash" not in columns:
            batch.add_column(
                sa.Column(
                    "credential_hash",
                    sa.String(length=64),
                    nullable=False,
                    server_default="",
                )
            )
        if "attested_at" not in columns:
            batch.add_column(sa.Column("attested_at", sa.DateTime(), nullable=True))
        if "attestation_expires_at" not in columns:
            batch.add_column(
                sa.Column("attestation_expires_at", sa.DateTime(), nullable=True)
            )
        if "attestation_grace_expires_at" not in columns:
            batch.add_column(
                sa.Column("attestation_grace_expires_at", sa.DateTime(), nullable=True)
            )
        if "revoked" not in columns:
            batch.add_column(
                sa.Column(
                    "revoked",
                    sa.Boolean(),
                    nullable=False,
                    server_default=sa.false(),
                )
            )

    indexes = _index_names()
    if "ix_installations_credential_hash" not in indexes:
        op.create_index(
            "ix_installations_credential_hash",
            "installations",
            ["credential_hash"],
            unique=False,
        )
    if "ix_installations_attestation_expires_at" not in indexes:
        op.create_index(
            "ix_installations_attestation_expires_at",
            "installations",
            ["attestation_expires_at"],
            unique=False,
        )
    if "ix_installations_revoked" not in indexes:
        op.create_index(
            "ix_installations_revoked",
            "installations",
            ["revoked"],
            unique=False,
        )


def downgrade() -> None:
    indexes = _index_names()
    for name in (
        "ix_installations_revoked",
        "ix_installations_attestation_expires_at",
        "ix_installations_credential_hash",
    ):
        if name in indexes:
            op.drop_index(name, table_name="installations")

    columns = _column_names()
    with op.batch_alter_table("installations") as batch:
        for name in (
            "revoked",
            "attestation_grace_expires_at",
            "attestation_expires_at",
            "attested_at",
            "credential_hash",
            "signing_public_key_b64",
        ):
            if name in columns:
                batch.drop_column(name)
