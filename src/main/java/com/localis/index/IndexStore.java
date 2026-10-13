package com.localis.index;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Indice persistente en SQLite (un unico fichero local, sin servidor). */
public final class IndexStore implements AutoCloseable {

    public record FileInfo(String path, String hash, int lines, boolean embedded) {}

    public record Stats(int files, int chunks, int embeddedChunks) {}

    private final Connection db;
    private final AtomicLong generation = new AtomicLong();

    public IndexStore(Path dbFile) {
        try {
            if (dbFile.getParent() != null) {
                Files.createDirectories(dbFile.getParent());
            }
            db = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
            try (Statement st = db.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("CREATE TABLE IF NOT EXISTS files (path TEXT PRIMARY KEY, hash TEXT NOT NULL, "
                        + "lines INTEGER NOT NULL, embedded INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS chunks (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "path TEXT NOT NULL, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL, "
                        + "kind TEXT NOT NULL, symbol TEXT, text TEXT NOT NULL, embedding BLOB)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_chunks_path ON chunks(path)");
            }
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo abrir el indice " + dbFile + ": " + e.getMessage(), e);
        }
    }

    /** Cambia cada vez que el contenido del indice cambia; permite invalidar caches. */
    public long generation() {
        return generation.get();
    }

    public synchronized Optional<FileInfo> file(String path) {
        try (PreparedStatement ps = db.prepareStatement("SELECT hash, lines, embedded FROM files WHERE path=?")) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new FileInfo(path, rs.getString(1), rs.getInt(2), rs.getInt(3) == 1))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized Set<String> filePaths() {
        Set<String> out = new HashSet<>();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("SELECT path FROM files")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** Sustituye de forma atomica un fichero y todos sus chunks. */
    public synchronized void replaceFile(String path, String hash, int lines, boolean embedded,
            List<CodeChunk> chunks) {
        try {
            db.setAutoCommit(false);
            try {
                deleteInternal(path);
                try (PreparedStatement f = db.prepareStatement("INSERT INTO files VALUES (?,?,?,?)");
                        PreparedStatement c = db.prepareStatement("INSERT INTO chunks (path,start_line,end_line,kind,"
                                + "symbol,text,embedding) VALUES (?,?,?,?,?,?,?)")) {
                    f.setString(1, path);
                    f.setString(2, hash);
                    f.setInt(3, lines);
                    f.setInt(4, embedded ? 1 : 0);
                    f.executeUpdate();
                    for (CodeChunk ch : chunks) {
                        c.setString(1, path);
                        c.setInt(2, ch.startLine());
                        c.setInt(3, ch.endLine());
                        c.setString(4, ch.kind());
                        c.setString(5, ch.symbol());
                        c.setString(6, ch.text());
                        c.setBytes(7, ch.embedding() == null ? null : toBytes(ch.embedding()));
                        c.addBatch();
                    }
                    c.executeBatch();
                }
                db.commit();
            } catch (SQLException e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(true);
            }
            generation.incrementAndGet();
        } catch (SQLException e) {
            throw new IllegalStateException("Error guardando " + path + ": " + e.getMessage(), e);
        }
    }

    public synchronized void deleteFile(String path) {
        try {
            deleteInternal(path);
            generation.incrementAndGet();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void deleteInternal(String path) throws SQLException {
        try (PreparedStatement a = db.prepareStatement("DELETE FROM chunks WHERE path=?");
                PreparedStatement b = db.prepareStatement("DELETE FROM files WHERE path=?")) {
            a.setString(1, path);
            a.executeUpdate();
            b.setString(1, path);
            b.executeUpdate();
        }
    }

    public synchronized List<CodeChunk> allChunks() {
        return query("SELECT id,path,start_line,end_line,kind,symbol,text,embedding FROM chunks ORDER BY id", null);
    }

    public synchronized List<CodeChunk> chunksOfFile(String path) {
        return query("SELECT id,path,start_line,end_line,kind,symbol,text,embedding FROM chunks WHERE path=? "
                + "ORDER BY start_line", path);
    }

    public synchronized Stats stats() {
        try (Statement st = db.createStatement();
                ResultSet rs = st.executeQuery("SELECT (SELECT COUNT(*) FROM files), (SELECT COUNT(*) FROM chunks), "
                        + "(SELECT COUNT(*) FROM chunks WHERE embedding IS NOT NULL)")) {
            rs.next();
            return new Stats(rs.getInt(1), rs.getInt(2), rs.getInt(3));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<CodeChunk> query(String sql, String param) {
        List<CodeChunk> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            if (param != null) {
                ps.setString(1, param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] emb = rs.getBytes(8);
                    out.add(new CodeChunk(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), emb == null ? null : toFloats(emb)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    static byte[] toBytes(float[] v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) {
            b.putFloat(f);
        }
        return b.array();
    }

    static float[] toFloats(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[bytes.length / 4];
        for (int i = 0; i < v.length; i++) {
            v[i] = b.getFloat();
        }
        return v;
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException ignored) {
            // nada que hacer al cerrar
        }
    }
}
