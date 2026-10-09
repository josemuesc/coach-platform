package com.coachplatform.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * scripts/db/app_runtime_role.sql on real PostgreSQL, run on top of the real migrations: the application's role sees and writes its
 * rows (BYPASSRLS), can neither TRUNCATE nor ALTER, cannot rewrite the append-only tables, and still can do what the application must
 * (e.g. set used_at on a reset link). The script is idempotent.
 */
class AppRuntimeRoleScriptIT extends PostgresIntegrationTest {

    private static final String PASSWORD = "it-only-password-1";
    private static final String DENIED = "42501";

    @Autowired JdbcTemplate jdbc;

    private void applyScript() throws Exception {
        jdbc.execute(Files.readString(Path.of("..", "scripts", "db", "app_runtime_role.sql")));
        jdbc.execute("ALTER ROLE app_runtime PASSWORD '" + PASSWORD + "'");   // set separately, exactly as the script's header says
    }

    private Connection asApp() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_runtime", PASSWORD);
    }

    private static String stateOf(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    @Test
    void theApplicationRoleCanDoItsJobAndNothingMore() throws Exception {
        applyScript();
        applyScript();   // idempotent
        UUID coach = jdbc.queryForObject("insert into coach (name, brand_name) values ('c', 'c') returning id", UUID.class);
        UUID coachUser = jdbc.queryForObject("insert into app_user (coach_id, email, password_hash, role) values (?, ?, 'h', 'COACH') returning id",
                UUID.class, coach, UUID.randomUUID() + "@test.co");
        UUID student = jdbc.queryForObject("insert into student (coach_id, full_name, email) values (?, 's', ?) returning id", UUID.class,
                coach, UUID.randomUUID() + "@test.co");
        UUID link = jdbc.queryForObject("insert into password_reset (coach_id, student_id, user_id, token_hash, created_by_user_id, expires_at) "
                + "select ?, ?, id, ?, id, now() + interval '1 day' from app_user where id = ? returning id", UUID.class, coach, student,
                "a".repeat(64), coachUser);

        try (Connection app = asApp()) {
            // role attributes
            assertThat(jdbc.queryForMap("select rolsuper, rolcreatedb, rolcreaterole, rolbypassrls from pg_roles where rolname = 'app_runtime'"))
                    .containsEntry("rolsuper", false).containsEntry("rolcreatedb", false).containsEntry("rolcreaterole", false)
                    .containsEntry("rolbypassrls", true);
            // it sees rows although RLS is on and has no policies, and writes ordinary tables
            try (Statement st = app.createStatement()) {
                var rs = st.executeQuery("select count(*) from student where id = '" + student + "'");
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            assertThat(stateOf(app, "update student set full_name = 'changed' where id = '" + student + "'")).isNull();
            // a reset link must stay updatable (used_at NULL -> value), but never deletable
            assertThat(stateOf(app, "update password_reset set used_at = now() where id = '" + link + "'")).isNull();
            assertThat(stateOf(app, "delete from password_reset where id = '" + link + "'")).isEqualTo(DENIED);
            // no TRUNCATE, no ALTER, no DROP, no creating objects
            assertThat(stateOf(app, "truncate student cascade")).isEqualTo(DENIED);
            assertThat(stateOf(app, "alter table student add column leaked text")).isEqualTo(DENIED);
            assertThat(stateOf(app, "drop table student cascade")).isEqualTo(DENIED);
            assertThat(stateOf(app, "create table public.sneaky (id int)")).isEqualTo(DENIED);
            assertThat(stateOf(app, "drop trigger trg_account_audit_immutable on account_audit")).isEqualTo(DENIED);
            // append-only tables: not even by the application
            for (String table : List.of("consent_record", "consent_revocation", "attendance_audit", "account_audit", "payment")) {
                assertThat(stateOf(app, "update " + table + " set coach_id = coach_id where false")).as("update " + table).isEqualTo(DENIED);
                assertThat(stateOf(app, "delete from " + table + " where false")).as("delete " + table).isEqualTo(DENIED);
                assertThat(stateOf(app, "insert into " + table + " select * from " + table + " where false")).as("insert " + table).isNull();
            }
            // Flyway's own table is none of its business
            assertThat(stateOf(app, "select 1 from flyway_schema_history")).isEqualTo(DENIED);
        }
    }

    @Test
    void theGrantsMatchTheIntentForEveryTable() throws Exception {
        applyScript();
        List<String> tables = jdbc.queryForList("select tablename from pg_tables where schemaname = 'public' and tablename <> 'flyway_schema_history'",
                String.class);
        assertThat(tables).contains("student", "password_reset", "account_audit", "attendance_audit");
        List<String> noUpdate = jdbc.queryForList("select distinct tg.tgrelid::regclass::text from pg_trigger tg join pg_proc p on p.oid = tg.tgfoid "
                + "where p.proname = 'forbid_row_change' and not tg.tgisinternal and (tg.tgtype & 16) <> 0", String.class);
        List<String> noDelete = jdbc.queryForList("select distinct tg.tgrelid::regclass::text from pg_trigger tg join pg_proc p on p.oid = tg.tgfoid "
                + "where p.proname = 'forbid_row_change' and not tg.tgisinternal and (tg.tgtype & 8) <> 0", String.class);
        assertThat(noUpdate).contains("consent_record", "consent_revocation", "attendance_audit", "account_audit", "payment").doesNotContain("password_reset");
        assertThat(noDelete).contains("password_reset", "account_audit", "payment");
        for (String t : tables) {
            assertThat(has(t, "SELECT")).as("select " + t).isTrue();
            assertThat(has(t, "INSERT")).as("insert " + t).isTrue();
            assertThat(has(t, "UPDATE")).as("update " + t).isEqualTo(!noUpdate.contains(t));
            assertThat(has(t, "DELETE")).as("delete " + t).isEqualTo(!noDelete.contains(t));
            assertThat(has(t, "TRUNCATE")).as("truncate " + t).isFalse();
            assertThat(jdbc.queryForObject("select tableowner from pg_tables where tablename = ?", String.class, t)).isNotEqualTo("app_runtime");
        }
        assertThat(has("flyway_schema_history", "SELECT")).isFalse();
    }

    private boolean has(String table, String privilege) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select has_table_privilege('app_runtime', 'public." + table + "', ?)", Boolean.class, privilege));
    }
}
