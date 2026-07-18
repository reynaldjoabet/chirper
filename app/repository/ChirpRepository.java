package repository;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import models.Chirp;
import play.Environment;
import play.db.Database;

/**
 * JDBC store behind Play's pooled {@link Database}. The SQL is deliberately vanilla so the H2
 * dev database and a production Postgres behave the same; evolutions own the schema.
 *
 * <p>Every method here blocks on a connection, so callers must run them on
 * {@link DatabaseExecutionContext}, never on the default dispatcher — the controller's
 * supplyAsync(..., ec) is that contract.
 *
 * <p>Timestamps are truncated to millis on write: the column stores more precision than some
 * drivers round-trip, and a created chirp must equal its later re-read exactly.
 */
@Singleton
public class ChirpRepository {

  private static final String COLUMNS = "id, author, body, created_at, likes";

  private final Database db;

  @Inject
  public ChirpRepository(Database db, Environment environment) {
    this.db = db;
    // Seed only the dev database, and only when empty: `sbt run` demos a populated timeline,
    // while production and tests start from exactly what is in the database.
    if (environment.isDev() && count() == 0) {
      create("ada", "Chirper: like a bird, but a website.");
      create("grace", "Shipped the frontend build pipeline. sbt and vite are friends now.");
      create("ada", "280 characters ought to be enough for anybody.");
    }
  }

  public Chirp create(String author, String body) {
    return db.withConnection(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "insert into chirps (author, body, created_at, likes) values (?, ?, ?, 0)",
                  Statement.RETURN_GENERATED_KEYS)) {
            Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            ps.setString(1, author);
            ps.setString(2, body);
            ps.setObject(3, createdAt.atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
              if (!keys.next()) {
                throw new SQLException("insert returned no generated key");
              }
              return new Chirp(keys.getLong(1), author, body, createdAt, 0);
            }
          }
        });
  }

  /** Newest first. Ids are monotonic, so id order is creation order. */
  public List<Chirp> list(Optional<String> author) {
    String sql =
        "select " + COLUMNS + " from chirps"
            + (author.isPresent() ? " where author = ?" : "")
            + " order by id desc";
    return db.withConnection(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (author.isPresent()) {
              ps.setString(1, author.get());
            }
            try (ResultSet rs = ps.executeQuery()) {
              List<Chirp> result = new ArrayList<>();
              while (rs.next()) {
                result.add(read(rs));
              }
              return result;
            }
          }
        });
  }

  public Optional<Chirp> find(long id) {
    // Braced body on purpose: an expression lambda is ambiguous between Database's void
    // ConnectionRunnable overload and the value-returning ConnectionCallable one.
    return db.withConnection(
        c -> {
          return selectOne(c, id);
        });
  }

  /** Atomic in SQL: concurrent likes each land, none lost. */
  public Optional<Chirp> like(long id) {
    return db.withTransaction(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("update chirps set likes = likes + 1 where id = ?")) {
            ps.setLong(1, id);
            if (ps.executeUpdate() == 0) {
              return Optional.empty();
            }
          }
          return selectOne(c, id);
        });
  }

  public boolean delete(long id) {
    return db.withConnection(
        c -> {
          try (PreparedStatement ps = c.prepareStatement("delete from chirps where id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
          }
        });
  }

  private long count() {
    return db.withConnection(
        c -> {
          try (PreparedStatement ps = c.prepareStatement("select count(*) from chirps");
              ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
          }
        });
  }

  private static Optional<Chirp> selectOne(Connection c, long id) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("select " + COLUMNS + " from chirps where id = ?")) {
      ps.setLong(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(read(rs)) : Optional.empty();
      }
    }
  }

  private static Chirp read(ResultSet rs) throws SQLException {
    return new Chirp(
        rs.getLong("id"),
        rs.getString("author"),
        rs.getString("body"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getInt("likes"));
  }
}
