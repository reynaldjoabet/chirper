package models;

import java.time.Instant;

import io.ebean.Finder;
import io.ebean.Model;
import io.ebean.annotation.WhenCreated;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Port of upstream tweet-service's Tweet, reduced to the phase-3 core: text + author + timestamp.
 * Media, polls, quotes, scheduling and reply threading are later phases; the API layer emits
 * their fields with defaults so the frontend's TweetResponse type is always satisfied.
 */
@Entity
@Table(name = "tweets")
public class Tweet extends Model {

  public static final Finder<Long, Tweet> find = new Finder<>(Tweet.class);

  @Id public Long id;

  @Column(nullable = false, length = 560)
  public String text;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "author_id")
  public User author;

  @WhenCreated
  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  // A reply points at its parent tweet; null for a top-level tweet. Ported in phase-3 interactions.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "reply_to_id")
  public Tweet replyTo;

  public Tweet() {}

  public Tweet(User author, String text) {
    this.author = author;
    this.text = text;
  }

  public Tweet(User author, String text, Tweet replyTo) {
    this.author = author;
    this.text = text;
    this.replyTo = replyTo;
  }

  /**
   * Read the author through an enhanced accessor: a field read from an unenhanced class (like a
   * controller) bypasses Ebean's lazy-loading interception and sees null on a reference bean.
   * This method body IS enhanced, so it triggers the load when needed.
   */
  public User loadAuthor() {
    return author;
  }
}
