# Repository Workflow

- After completing and verifying each requested change, stage only files related to the completed work, write a concise Chinese Conventional Commit message, and commit automatically.
- Do not commit secrets, local configuration, IDE metadata, caches, logs, or generated build output.
- If the user explicitly asks not to commit, follow that request for the current task.
- Any change to modules, runtime flows, APIs, database migrations, configuration, deployment, permissions, or operational procedures must update `docs/ARCHITECTURE.md` in the same commit.
- Keep the Quick Start commands in `README.md` and `docs/ARCHITECTURE.md` executable and consistent with the currently supported JDK, Node.js, database schema, and Agent versions.
- Treat committed Flyway migrations as immutable. Every database change after V13 must add a new versioned `.sql` file under `backend/src/main/resources/db/migration/` or `backend/src/main/resources/db/mysql-migration/`; do not add Java migrations.
- Modify existing tables in place with `ALTER TABLE`. Never append migration versions such as `_v14` or `_v15` to table names, including temporary or shadow table names.
- In the same database-change commit, update `deploy/mysql/logm-init.sql` to the latest final schema and keep its baseline version synchronized with `.env.example`, `application-prod.yml`, `README.md`, and `docs/ARCHITECTURE.md`.

## Versioning

- The latest formal release is `v1.0.0`. Track the latest formal release separately from the current source version; update the release record only when a formal release is completed.
- Every ordinary commit, including documentation and workflow changes, increments the current source patch version by one: `v1.0.1` -> `v1.0.2`.
- Every formal release increments the current minor version by one and resets patch to zero: `v1.0.2` -> `v1.1.0`.
- Only when the user explicitly requests a major release ("大版本发布"), increment major by one and reset minor and patch to zero: `v1.x.y` -> `v2.0.0`. Never infer a major release from breaking changes.
- A release commit applies only its release increment; do not add an extra patch increment. Building release archives alone does not constitute a formal release or change the version.
- Before committing, synchronize `backend/pom.xml`, `agent/pom.xml`, `frontend/package.json`, the root package versions in `frontend/package-lock.json`, the Agent runtime version, affected test fixtures, and current-version documentation and artifact examples. Do not change dependency versions or Flyway versions as part of an application version increment.
