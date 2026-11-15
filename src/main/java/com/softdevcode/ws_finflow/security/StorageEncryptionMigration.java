package com.softdevcode.ws_finflow.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** One transaction: locks, conversion, backfill, authenticated read-back, guards, commit.
 * No plaintext backup or key is persisted in PostgreSQL. A separate Neon branch is
 * the recovery checkpoint. Never run against production before branch verification.
 */
@Component("storageEncryption")
@Order(-1000)
public class StorageEncryptionMigration implements ApplicationRunner, HealthIndicator {
    private static final int VERSION = 3;
    private final DataSource dataSource;
    private final FieldCipher cipher;
    private volatile boolean ready;
    public StorageEncryptionMigration(DataSource dataSource, FieldCipher cipher) {
        this.dataSource = dataSource; this.cipher = cipher;
    }

    public record Field(String column, String context) {
        public Field(String column) { this(column, null); }
        public String context(String table) { return context == null ? table + "." + column : context; }
    }
    public record Table(String name, String key, List<Field> fields) {}
    private static Table table(String name, String key, String... columns) {
        return new Table(name,key,Arrays.stream(columns).map(Field::new).toList());
    }
    public static final List<Table> TABLES = List.of(
        table("users","id","email","username","first_name","last_name","avatar_url","timezone","refresh_token"),
        table("categories","id","name"),
        table("transactions","id","amount","description","tags","date"),
        table("goals","id","title","description","target_amount","current_amount","deadline"),
        table("goal_contributions","id","amount","note"),
        table("monthly_budgets","id","limit_amount"),
        table("budgets","id","amount_limit"),
        table("planned_expenses","id","title","amount","notes","due_date","remind_at"),
        table("financial_accounts","id","name","opening_balance"),
        table("account_transfers","id","amount","note","transfer_date"),
        table("recurring_rules","id","title","amount","day_of_month","next_due_date"),
        table("privacy_rights_requests","id","description"),
        table("profile_email_challenges","user_id","new_email"),
        table("billing_entitlements","user_id","reason"),
        table("billing_admin_audit","id","subject","details"),
        table("finflow_push_subscriptions","endpoint","endpoint_cipher","p256dh","auth"),
        new Table("google_account_links","google_sub",List.of(
            new Field("google_sub_cipher","google_account_links.google_sub"),new Field("google_email"))),
        new Table("transactions_log","id",List.of(
            new Field("old_amount","transactions.amount"),new Field("new_amount","transactions.amount"),
            new Field("old_description","transactions.description"),new Field("new_description","transactions.description"),
            new Field("old_date","transactions.date"),new Field("new_date","transactions.date")))
    );

    @Override public void run(ApplicationArguments arguments) throws Exception { migrate(); }

    public void migrate() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection,"SET LOCAL lock_timeout='10s'");
                execute(connection,"SET LOCAL statement_timeout='120s'");
                execute(connection,"SELECT pg_advisory_xact_lock(607151114)");
                boolean applied = exists(connection,"finflow_storage_encryption");
                int appliedVersion=0;
                if (applied) {
                    if (!cipher.enabled()) throw new IllegalStateException("Encrypted storage cannot start with encryption disabled");
                    try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery(
                        "SELECT key_check,version FROM finflow_storage_encryption ORDER BY version DESC LIMIT 1")) {
                        if (!r.next() || !MessageDigest.isEqual(r.getString(1).getBytes(StandardCharsets.UTF_8),
                                cipher.lookup("storage-key-check","v1").getBytes(StandardCharsets.UTF_8)))
                            throw new IllegalStateException("Storage encryption key does not match; no data changed");
                        appliedVersion=r.getInt(2);
                        if(appliedVersion>VERSION) throw new IllegalStateException("Storage schema is newer than this application");
                    }
                    if(appliedVersion==VERSION) {
                        for (Table table : TABLES) if (exists(connection,table.name())) fingerprint(connection,table,true);
                        connection.commit(); ready=true; return;
                    }
                }
                // With encryption disabled these are additive compatibility columns only.
                additiveColumns(connection);
                if (!cipher.enabled()) { connection.commit(); ready=true; return; }
                List<Table> tables = TABLES.stream().filter(t -> exists(connection,t.name())).toList();
                for (Table table : tables.stream().sorted(Comparator.comparing(Table::name)).toList())
                    execute(connection,"LOCK TABLE "+table.name()+" IN ACCESS EXCLUSIVE MODE");
                backfillLookups(connection);
                for (Table table : tables) {
                    byte[] before = fingerprint(connection,table,false);
                    convertTable(connection,table);
                    if (!MessageDigest.isEqual(before,fingerprint(connection,table,true)))
                        throw new IllegalStateException("Storage read-back mismatch: "+table.name());
                    guardTable(connection,table);
                }
                replaceLookupIndexes(connection);
                execute(connection,"CREATE TABLE IF NOT EXISTS finflow_storage_encryption (version integer PRIMARY KEY, key_check varchar(64) NOT NULL, completed_at timestamptz NOT NULL DEFAULT now())");
                try (PreparedStatement s=connection.prepareStatement("INSERT INTO finflow_storage_encryption(version,key_check) VALUES (?,?)")) {
                    s.setInt(1,VERSION);s.setString(2,cipher.lookup("storage-key-check","v1"));s.executeUpdate();
                }
                connection.commit(); ready=true;
            } catch (Exception error) {
                connection.rollback();
                // Never include a JDBC exception's SQL parameters or private row values.
                throw new IllegalStateException("Storage migration rolled back: "+error.getClass().getSimpleName());
            }
        }
    }

    private void additiveColumns(Connection c) throws SQLException {
        for (String[] item : List.of(new String[]{"users","email_lookup"},new String[]{"users","username_lookup"},
                new String[]{"categories","name_lookup"},new String[]{"financial_accounts","name_lookup"},
                new String[]{"finflow_push_subscriptions","endpoint_cipher"},new String[]{"google_account_links","google_sub_cipher"}))
            if (exists(c,item[0])) execute(c,"ALTER TABLE "+item[0]+" ADD COLUMN IF NOT EXISTS "+item[1]+" text");
        if (!cipher.enabled()) {
            if (exists(c,"users")) execute(c,"UPDATE users SET email_lookup=email,username_lookup=username WHERE email_lookup IS NULL OR username_lookup IS NULL");
            if (exists(c,"categories")) execute(c,"UPDATE categories SET name_lookup=lower(name) WHERE name_lookup IS NULL");
            if (exists(c,"financial_accounts")) execute(c,"UPDATE financial_accounts SET name_lookup=lower(trim(name)) WHERE name_lookup IS NULL");
            if (exists(c,"finflow_push_subscriptions")) execute(c,"UPDATE finflow_push_subscriptions SET endpoint_cipher=endpoint WHERE endpoint_cipher IS NULL");
        }
        if (exists(c,"google_account_links"))
            execute(c,"UPDATE google_account_links SET google_sub_cipher=google_sub WHERE google_sub_cipher IS NULL");
        if (cipher.enabled() && exists(c,"finflow_push_subscriptions"))
            execute(c,"UPDATE finflow_push_subscriptions SET endpoint_cipher=endpoint WHERE endpoint_cipher IS NULL");
    }

    private void backfillLookups(Connection c) throws SQLException {
        for (String[] item : List.of(new String[]{"users","email","email_lookup"},new String[]{"users","username","username_lookup"},
                new String[]{"categories","name","name_lookup"},new String[]{"financial_accounts","name","name_lookup"})) {
            if (!exists(c,item[0])) continue;
            boolean previouslyProtected=protectedColumn(c,item[0],item[1]);
            try (Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT id,"+item[1]+" FROM "+item[0]);
                 PreparedStatement update=c.prepareStatement("UPDATE "+item[0]+" SET "+item[2]+"=? WHERE id=?")) {
                while(rs.next()) {
                    String value=rs.getString(2);
                    if(previouslyProtected) value=cipher.decrypt(item[0]+"."+item[1],value);
                    if ("name".equals(item[1]) && value!=null) value=value.toLowerCase(Locale.ROOT);
                    if ("financial_accounts".equals(item[0]) && value!=null) value=value.trim();
                    update.setString(1,cipher.lookup(item[0]+"."+item[1],value));update.setObject(2,rs.getObject(1));update.addBatch();
                } update.executeBatch();
            }
        }
    }

    private void convertTable(Connection c, Table table) throws Exception {
        Set<String> columns=columns(c,table.name());
        Set<String> protectedFields=protectedColumns(c,table.name());
        List<Field> fields=table.fields().stream().filter(f -> columns.contains(f.column()) &&
            !protectedFields.contains(f.column())).toList();
        if(fields.isEmpty()) return;
        // Only remove checks whose input representation is being replaced. Identifier,
        // ownership, type, status, date and uniqueness constraints stay in force.
        try(PreparedStatement s=c.prepareStatement("SELECT conname FROM pg_constraint WHERE conrelid=?::regclass AND contype='c' AND conkey && ARRAY(SELECT attnum FROM pg_attribute WHERE attrelid=?::regclass AND attname=ANY(?))::smallint[]")) {
            s.setString(1,table.name());s.setString(2,table.name());
            s.setArray(3,c.createArrayOf("text",fields.stream().map(Field::column).toArray()));
            List<String> names=new ArrayList<>();try(ResultSet r=s.executeQuery()){while(r.next())names.add(r.getString(1));}
            for(String name:names) execute(c,"ALTER TABLE "+table.name()+" DROP CONSTRAINT "+quote(name));
        }
        for(Field field:fields) {
            execute(c,"ALTER TABLE "+table.name()+" ALTER COLUMN "+quote(field.column())+" DROP DEFAULT");
            execute(c,"ALTER TABLE "+table.name()+" ALTER COLUMN "+quote(field.column())+" TYPE text USING "+quote(field.column())+"::text");
        }
        String set=String.join(",",fields.stream().map(f -> quote(f.column())+"=?").toList());
        try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT * FROM "+table.name()+" ORDER BY "+table.key());
            PreparedStatement update=c.prepareStatement("UPDATE "+table.name()+" SET "+set+" WHERE "+table.key()+"=?")) {
            while(rs.next()) {
                int index=1;
                for(Field field:fields) {
                    String value=rs.getString(field.column());
                    if(value!=null && value.startsWith(FieldCipher.PREFIX))
                        throw new IllegalStateException("Untracked ciphertext requires manual recovery");
                    String context="endpoint_cipher".equals(field.column())?"finflow_push_subscriptions.endpoint":field.context(table.name());
                    update.setString(index++,cipher.encrypt(context,value));
                }
                update.setObject(index,rs.getObject(table.key()));update.addBatch();
            }update.executeBatch();
        }
        if(keyContext(table)!=null) {
            try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT "+table.key()+" FROM "+table.name());
                PreparedStatement update=c.prepareStatement("UPDATE "+table.name()+" SET "+table.key()+"=? WHERE "+table.key()+"=?")) {
                while(rs.next()){String value=rs.getString(1);update.setString(1,cipher.lookup(keyContext(table),value));update.setString(2,value);update.addBatch();}
                update.executeBatch();
            }
        }
    }

    private byte[] fingerprint(Connection c,Table table,boolean decrypt) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        Set<String> columns=columns(c,table.name());
        Set<String> protectedFields=protectedColumns(c,table.name());
        // Push's key is replaced by its HMAC; authenticate its preserved endpoint instead.
        String order=table.key();
        Map<String,byte[]> rows=new TreeMap<>();
        try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT * FROM "+table.name()+" ORDER BY "+order)) {
            while(rs.next()) {
                String rowKey=rs.getString(table.key());
                if(keyContext(table)!=null && !decrypt && !protectedFields.contains(table.key()+"_cipher"))
                    rowKey=cipher.lookup(keyContext(table),rowKey);
                MessageDigest row=MessageDigest.getInstance("SHA-256");
                for(Field field:table.fields()) if(columns.contains(field.column())) {
                    String value=rs.getString(field.column());
                    String context="endpoint_cipher".equals(field.column())?"finflow_push_subscriptions.endpoint":field.context(table.name());
                    if(decrypt || protectedFields.contains(field.column())) value=cipher.decrypt(context,value);
                    byte[] raw=value==null?new byte[0]:value.getBytes(StandardCharsets.UTF_8);
                    row.update((byte)(value==null?0:1));
                    row.update(java.nio.ByteBuffer.allocate(4).putInt(raw.length).array());row.update(raw);
                }
                rows.put(rowKey,row.digest());
            }
        }
        for(var row:rows.entrySet()) { digest.update(row.getKey().getBytes(StandardCharsets.UTF_8));digest.update(row.getValue()); }
        return digest.digest();
    }

    private void guardTable(Connection c,Table table) throws SQLException {
        Set<String> columns=columns(c,table.name());
        Set<String> protectedFields=protectedColumns(c,table.name());
        for(Field field:table.fields()) if(columns.contains(field.column()) && !protectedFields.contains(field.column()))
            execute(c,"ALTER TABLE "+table.name()+" ADD CONSTRAINT "+quote("ffenc_"+field.column())+
                " CHECK ("+quote(field.column())+" IS NULL OR "+quote(field.column())+" ~ '^ffenc:v1:[A-Za-z0-9_-]{38,}$')");
    }

    private void replaceLookupIndexes(Connection c) throws SQLException {
        if(exists(c,"users")) {
            execute(c,"CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lookup ON users(email_lookup)");
            execute(c,"CREATE UNIQUE INDEX IF NOT EXISTS uq_users_username_lookup ON users(username_lookup)");
            execute(c,"ALTER TABLE users ALTER COLUMN email_lookup SET NOT NULL,ALTER COLUMN username_lookup SET NOT NULL");
            execute(c,"ALTER TABLE users DROP CONSTRAINT IF EXISTS users_admin_email_guard");
            // The original guard contained the owner's plaintext email. Preserve the
            // same existing owner by ID, alongside the existing single-admin index.
            execute(c,"DO $$ DECLARE owner_id bigint; BEGIN SELECT id INTO owner_id FROM users WHERE role='ADMIN'; IF owner_id IS NULL THEN ALTER TABLE users ADD CONSTRAINT users_admin_email_guard CHECK(role <> 'ADMIN'); ELSE EXECUTE format('ALTER TABLE users ADD CONSTRAINT users_admin_email_guard CHECK(role <> ''ADMIN'' OR id=%s)',owner_id); END IF; END $$");
        }
        if(exists(c,"categories")) {
            execute(c,"DROP INDEX IF EXISTS idx_categories_global_name_type");execute(c,"DROP INDEX IF EXISTS idx_categories_owner_name_type");
            execute(c,"CREATE UNIQUE INDEX idx_categories_global_name_type ON categories(name_lookup,lower(type)) WHERE user_id IS NULL");
            execute(c,"CREATE UNIQUE INDEX idx_categories_owner_name_type ON categories(user_id,name_lookup,lower(type)) WHERE user_id IS NOT NULL");
            execute(c,"ALTER TABLE categories ALTER COLUMN name_lookup SET NOT NULL");
        }
        if(exists(c,"financial_accounts")) {
            execute(c,"DROP INDEX IF EXISTS uq_financial_accounts_owner_name");
            execute(c,"CREATE UNIQUE INDEX uq_financial_accounts_owner_name ON financial_accounts(user_id,name_lookup)");
            execute(c,"ALTER TABLE financial_accounts ALTER COLUMN name_lookup SET NOT NULL");
        }
    }

    private static String quote(String name) { return "\""+name.replace("\"","\"\"")+"\""; }
    private static String keyContext(Table table) {
        return Set.of("finflow_push_subscriptions","google_account_links").contains(table.name())?
            table.name()+"."+table.key():null;
    }
    private static boolean protectedColumn(Connection c,String table,String column) {
        return protectedColumns(c,table).contains(column);
    }
    private static Set<String> protectedColumns(Connection c,String table) {
        Set<String> result=new HashSet<>();
        try(PreparedStatement s=c.prepareStatement("SELECT conname FROM pg_constraint WHERE conrelid=?::regclass AND conname LIKE 'ffenc_%'")) {
            s.setString(1,table);try(ResultSet r=s.executeQuery()){while(r.next())result.add(r.getString(1).substring(6));}
            return result;
        }catch(SQLException e){throw new IllegalStateException("Encryption guard inspection failed");}
    }
    private static void execute(Connection c,String sql) throws SQLException { try(Statement s=c.createStatement()){s.execute(sql);} }
    private static boolean exists(Connection c,String table) {
        try(PreparedStatement s=c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")){s.setString(1,"public."+table);try(ResultSet r=s.executeQuery()){r.next();return r.getBoolean(1);}}
        catch(SQLException e){throw new IllegalStateException("Storage schema inspection failed");}
    }
    private static Set<String> columns(Connection c,String table) throws SQLException {
        Set<String> names=new LinkedHashSet<>();
        try(PreparedStatement s=c.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=? ORDER BY ordinal_position")) {
            s.setString(1,table);try(ResultSet r=s.executeQuery()){while(r.next())names.add(r.getString(1));}
        }return names;
    }
    @Override public Health health() { return ready?Health.up().build():Health.down().build(); }
}
