package models;

import java.time.Instant;

import io.ebean.Finder;
import io.ebean.Model;
import io.ebean.annotation.WhenCreated;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single posted chirp, persisted via Ebean (see {@code ebean.default} in application.conf).
 *
 * <p>Fields are public and accessed directly rather than through getters/setters: Ebean's
 * bytecode enhancer generates accessors after compilation, so code in this same module cannot
 * call them at compile time. This mirrors Play's own Ebean examples.
 */
@Entity
@Table(name = "chirps")
public class Chirp extends Model {

  public static final Finder<Long, Chirp> find = new Finder<>(Chirp.class);

  @Id public Long id;

  @Column(nullable = false, length = 15)
  public String author;

  @Column(nullable = false, length = 560)
  public String body;

  // Ebean sets this on insert; no application code ever assigns it.
  @WhenCreated
  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(nullable = false)
  public int likes;

  public Chirp() {}

  public Chirp(String author, String body) {
    this.author = author;
    this.body = body;
  }
}
