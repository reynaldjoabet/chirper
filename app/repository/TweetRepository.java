package repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.ebean.Database;
import io.ebean.PagedList;
import io.ebean.SqlRow;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.Tweet;
import models.User;

/** Blocking Ebean queries; callers run them on {@link DatabaseExecutionContext}. */
@Singleton
public class TweetRepository {

  public static final int PAGE_SIZE = 10;

  private final Database db;

  @Inject
  public TweetRepository(Database db) {
    this.db = db;
  }

  public Tweet create(User author, String text) {
    Tweet tweet = new Tweet(author, text);
    tweet.save();
    return tweet;
  }

  public Tweet reply(User author, Tweet parent, String text) {
    Tweet tweet = new Tweet(author, text, parent);
    tweet.save();
    return tweet;
  }

  /** Newest first, top-level tweets only (replies show under their parent). */
  public PagedList<Tweet> page(int page) {
    return Tweet.find
        .query()
        .fetch("author")
        .where()
        .isNull("replyTo")
        .orderBy("id desc")
        .setFirstRow(page * PAGE_SIZE)
        .setMaxRows(PAGE_SIZE)
        .findPagedList();
  }

  public List<Tweet> byAuthor(long userId, int page) {
    return Tweet.find
        .query()
        .fetch("author")
        .where()
        .eq("author.id", userId)
        .isNull("replyTo")
        .orderBy("id desc")
        .setFirstRow(page * PAGE_SIZE)
        .setMaxRows(PAGE_SIZE)
        .findList();
  }

  public Optional<Tweet> find(long id) {
    return Optional.ofNullable(Tweet.find.byId(id));
  }

  public long countByAuthor(long userId) {
    return Tweet.find.query().where().eq("author.id", userId).findCount();
  }

  // --- interactions: toggle returns the new state (true = now liked/retweeted) ---

  public boolean toggleLike(long userId, long tweetId) {
    return toggle("tweet_likes", userId, tweetId);
  }

  public boolean toggleRetweet(long userId, long tweetId) {
    return toggle("tweet_retweets", userId, tweetId);
  }

  private boolean toggle(String table, long userId, long tweetId) {
    int deleted =
        db.sqlUpdate("delete from " + table + " where user_id = :u and tweet_id = :t")
            .setParameter("u", userId)
            .setParameter("t", tweetId)
            .execute();
    if (deleted > 0) {
      return false; // was present, now removed
    }
    db.sqlUpdate(
            "insert into " + table + " (user_id, tweet_id, created_at) values (:u, :t, :now)")
        .setParameter("u", userId)
        .setParameter("t", tweetId)
        .setParameter("now", Instant.now())
        .execute();
    return true;
  }

  /**
   * Per-page interaction stats in a fixed number of queries (not one-per-tweet). Counts for
   * likes, retweets and replies across the page's tweets, plus which of them the given user has
   * liked/retweeted. currentUserId may be null (a logged-out reader).
   */
  public Stats statsFor(List<Long> tweetIds, Long currentUserId) {
    Stats s = new Stats();
    if (tweetIds.isEmpty()) {
      return s;
    }
    String ids = inClause(tweetIds);
    countInto(s.likes, "select tweet_id, count(*) c from tweet_likes where tweet_id in " + ids + " group by tweet_id");
    countInto(s.retweets, "select tweet_id, count(*) c from tweet_retweets where tweet_id in " + ids + " group by tweet_id");
    countInto(s.replies, "select reply_to_id tweet_id, count(*) c from tweets where reply_to_id in " + ids + " group by reply_to_id");
    if (currentUserId != null) {
      idsInto(s.likedByUser, "select tweet_id from tweet_likes where user_id = " + currentUserId + " and tweet_id in " + ids);
      idsInto(s.retweetedByUser, "select tweet_id from tweet_retweets where user_id = " + currentUserId + " and tweet_id in " + ids);
    }
    return s;
  }

  private void countInto(Map<Long, Long> target, String sql) {
    for (SqlRow row : db.sqlQuery(sql).findList()) {
      target.put(row.getLong("tweet_id"), row.getLong("c"));
    }
  }

  private void idsInto(Set<Long> target, String sql) {
    for (SqlRow row : db.sqlQuery(sql).findList()) {
      target.add(row.getLong("tweet_id"));
    }
  }

  // tweetIds come from our own rows (never user input), so inlining them is injection-safe and
  // avoids per-driver named-list-parameter differences.
  private static String inClause(List<Long> ids) {
    StringBuilder sb = new StringBuilder("(");
    for (int i = 0; i < ids.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(ids.get(i).longValue());
    }
    return sb.append(')').toString();
  }

  public static final class Stats {
    public final Map<Long, Long> likes = new HashMap<>();
    public final Map<Long, Long> retweets = new HashMap<>();
    public final Map<Long, Long> replies = new HashMap<>();
    public final Set<Long> likedByUser = new HashSet<>();
    public final Set<Long> retweetedByUser = new HashSet<>();

    public long likes(long id) {
      return likes.getOrDefault(id, 0L);
    }

    public long retweets(long id) {
      return retweets.getOrDefault(id, 0L);
    }

    public long replies(long id) {
      return replies.getOrDefault(id, 0L);
    }
  }
}
