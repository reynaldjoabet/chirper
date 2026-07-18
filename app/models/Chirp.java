package models;

import java.time.Instant;

/** A single posted chirp. Immutable; "liking" produces a copy with the count bumped. */
public record Chirp(long id, String author, String body, Instant createdAt, int likes) {

  public Chirp liked() {
    return new Chirp(id, author, body, createdAt, likes + 1);
  }
}
