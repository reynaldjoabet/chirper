package repository;

import java.util.List;
import java.util.Optional;

import io.ebean.Database;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.Chirp;
import play.Environment;

/**
 * Ebean-backed store, on top of the {@code default} server configured via {@code ebean.default}
 * in application.conf. The schema (conf/evolutions) is deliberately vanilla SQL so the H2 dev
 * database and a production Postgres behave the same.
 *
 * <p>Every method here blocks on a connection, so callers must run them on
 * {@link DatabaseExecutionContext}, never on the default dispatcher — the controller's
 * supplyAsync(..., ec) is that contract.
 */
@Singleton
public class ChirpRepository {

  private final Database db;

  @Inject
  public ChirpRepository(Database db, Environment environment) {
    this.db = db;
    // Seed only the dev database, and only when empty: `sbt run` demos a populated timeline,
    // while production and tests start from exactly what is in the database.
    if (environment.isDev() && Chirp.find.query().findCount() == 0) {
      create("ada", "Chirper: like a bird, but a website.");
      create("grace", "Shipped the frontend build pipeline. sbt and vite are friends now.");
      create("ada", "280 characters ought to be enough for anybody.");
    }
  }

  public Chirp create(String author, String body) {
    Chirp chirp = new Chirp(author, body);
    chirp.save();
    return chirp;
  }

  /** Newest first. Ids are monotonic, so id order is creation order. */
  public List<Chirp> list(Optional<String> author) {
    var query = Chirp.find.query().orderBy("id desc");
    author.ifPresent(a -> query.where().eq("author", a));
    return query.findList();
  }

  public Optional<Chirp> find(long id) {
    return Optional.ofNullable(Chirp.find.byId(id));
  }

  /** Atomic in SQL: concurrent likes each land, none lost. */
  public Optional<Chirp> like(long id) {
    int rows =
        db.sqlUpdate("update chirps set likes = likes + 1 where id = ?").setParameter(1, id).execute();
    return rows == 0 ? Optional.empty() : find(id);
  }

  public boolean delete(long id) {
    return db.delete(Chirp.class, id) > 0;
  }
}
