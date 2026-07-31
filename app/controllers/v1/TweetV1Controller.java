package controllers.v1;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.supplyAsync;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import auth.CurrentUser;
import io.ebean.PagedList;
import jakarta.inject.Inject;
import models.Tweet;
import models.User;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import repository.DatabaseExecutionContext;
import repository.TweetRepository;

/**
 * Port of upstream tweet-service's core read/write surface (PORTING.md phase 3, first slice):
 * the home feed, single tweets, a user's tweets, and posting. Responses are bare
 * TweetResponse shapes (no envelope) with upstream's `page-total-count` header driving the
 * frontend's infinite scroll. Fields for unported features (media, polls, quotes, likes,
 * retweets) are emitted with honest defaults so the frontend's type always parses.
 */
public class TweetV1Controller extends Controller {

  private static final String PAGE_TOTAL_COUNT = "page-total-count";
  private static final int MAX_TEXT = 280;

  private final TweetRepository tweets;
  private final CurrentUser currentUser;
  private final DatabaseExecutionContext ec;

  @Inject
  public TweetV1Controller(
      TweetRepository tweets, CurrentUser currentUser, DatabaseExecutionContext ec) {
    this.tweets = tweets;
    this.currentUser = currentUser;
    this.ec = ec;
  }

  // GET /ui/v1/tweets?page=N
  public CompletionStage<Result> list(Http.Request request) {
    int page = request.queryString("page").map(Integer::parseInt).orElse(0);
    return supplyAsync(
        () -> {
          Long me = currentUser.resolve(request).map(u -> u.id).orElse(null);
          PagedList<Tweet> paged = tweets.page(page);
          return ok(pageJson(paged.getList(), me))
              .withHeader(PAGE_TOTAL_COUNT, String.valueOf(paged.getTotalCount()));
        },
        ec);
  }

  // GET /ui/v1/tweets/:id
  public CompletionStage<Result> get(Long id, Http.Request request) {
    return supplyAsync(
        () -> {
          Long me = currentUser.resolve(request).map(u -> u.id).orElse(null);
          return tweets
              .find(id)
              .map(t -> ok(pageJson(List.of(t), me).get(0)))
              .orElseGet(() -> notFound(message("Tweet not found")));
        },
        ec);
  }

  // GET /ui/v1/tweets/user/:userId?page=N
  public CompletionStage<Result> byUser(Long userId, Http.Request request) {
    int page = request.queryString("page").map(Integer::parseInt).orElse(0);
    return supplyAsync(
        () -> {
          Long me = currentUser.resolve(request).map(u -> u.id).orElse(null);
          List<Tweet> list = tweets.byAuthor(userId, page);
          // Total count per author is a later refinement; a page-sized count still stops the
          // frontend's infinite scroll correctly once a short page arrives.
          return ok(pageJson(list, me)).withHeader(PAGE_TOTAL_COUNT, String.valueOf(list.size()));
        },
        ec);
  }

  // POST /ui/v1/tweets
  public CompletionStage<Result> create(Http.Request request) {
    JsonNode json = request.body().asJson();
    if (json == null) {
      return completedFuture(badRequest(message("expected a JSON body")));
    }
    String text = json.path("text").asText("").trim();
    if (text.isEmpty()) {
      return completedFuture(badRequest(message("Tweet text length must be filled")));
    }
    if (text.codePointCount(0, text.length()) > MAX_TEXT) {
      return completedFuture(
          badRequest(message("Tweet text length must be less than " + MAX_TEXT)));
    }
    return supplyAsync(
        () -> {
          Optional<User> author = currentUser.resolve(request);
          if (author.isEmpty()) {
            return unauthorized(message("JWT expired or invalid"));
          }
          Tweet created = tweets.create(author.get(), text);
          return ok(pageJson(List.of(created), author.get().id).get(0));
        },
        ec);
  }

  // POST /ui/v1/tweets/reply/:userId/:tweetId  (userId in the path is upstream's shape; the real
  // author is the JWT, never the path)
  public CompletionStage<Result> reply(Long userId, Long tweetId, Http.Request request) {
    JsonNode json = request.body().asJson();
    String text = json == null ? "" : json.path("text").asText("").trim();
    if (text.isEmpty()) {
      return completedFuture(badRequest(message("Tweet text length must be filled")));
    }
    if (text.codePointCount(0, text.length()) > MAX_TEXT) {
      return completedFuture(
          badRequest(message("Tweet text length must be less than " + MAX_TEXT)));
    }
    return supplyAsync(
        () -> {
          Optional<User> author = currentUser.resolve(request);
          if (author.isEmpty()) {
            return unauthorized(message("JWT expired or invalid"));
          }
          return tweets
              .find(tweetId)
              .map(
                  parent -> {
                    Tweet r = tweets.reply(author.get(), parent, text);
                    return ok(pageJson(List.of(r), author.get().id).get(0));
                  })
              .orElseGet(() -> notFound(message("Tweet not found")));
        },
        ec);
  }

  // GET /ui/v1/tweets/like/:userId/:tweetId — toggles; returns NotificationTweetResponse.
  public CompletionStage<Result> like(Long userId, Long tweetId, Http.Request request) {
    return interaction(request, tweetId, (me) -> tweets.toggleLike(me, tweetId));
  }

  // GET /ui/v1/tweets/retweet/:userId/:tweetId — toggles; returns NotificationTweetResponse.
  public CompletionStage<Result> retweet(Long userId, Long tweetId, Http.Request request) {
    return interaction(request, tweetId, (me) -> tweets.toggleRetweet(me, tweetId));
  }

  private CompletionStage<Result> interaction(
      Http.Request request, Long tweetId, java.util.function.LongFunction<Boolean> toggle) {
    return supplyAsync(
        () -> {
          Optional<User> me = currentUser.resolve(request);
          if (me.isEmpty()) {
            return unauthorized(message("JWT expired or invalid"));
          }
          return tweets
              .find(tweetId)
              .map(
                  t -> {
                    boolean nowOn = toggle.apply(me.get().id);
                    // NotificationTweetResponse: the reducer reads id + notificationCondition.
                    ObjectNode n = Json.newObject();
                    n.put("id", t.id);
                    n.put("text", t.text);
                    n.put("authorId", t.loadAuthor().id);
                    n.put("notificationCondition", nowOn);
                    return ok(n);
                  })
              .orElseGet(() -> notFound(message("Tweet not found")));
        },
        ec);
  }

  private ArrayNode pageJson(List<Tweet> list, Long currentUserId) {
    List<Long> ids = list.stream().map(t -> t.id).toList();
    TweetRepository.Stats stats = tweets.statsFor(ids, currentUserId);
    ArrayNode body = Json.newArray();
    list.forEach(t -> body.add(toJson(t, stats)));
    return body;
  }

  /** Upstream's TweetResponse: every field the frontend type reads, defaults where unported. */
  private static ObjectNode toJson(Tweet t, TweetRepository.Stats stats) {
    ObjectNode n = Json.newObject();
    n.put("id", t.id);
    n.put("text", t.text);
    n.put("tweetType", "TWEET");
    n.put("createdAt", t.createdAt.toString());
    n.putNull("scheduledDate");
    n.putNull("addressedUsername");
    n.putNull("addressedId");
    n.putNull("addressedTweetId");
    n.put("replyType", "EVERYONE");
    n.putNull("link");
    n.putNull("linkTitle");
    n.putNull("linkDescription");
    n.putNull("linkCover");
    n.putNull("linkCoverSize");
    n.putNull("gifImage");
    n.set("author", authorJson(t.loadAuthor()));
    n.set("images", Json.newArray());
    n.putNull("imageDescription");
    n.set("taggedImageUsers", Json.newArray());
    n.putNull("quoteTweet");
    n.putNull("tweetList");
    n.putNull("poll");
    n.put("retweetsCount", stats.retweets(t.id));
    n.put("likesCount", stats.likes(t.id));
    n.put("repliesCount", stats.replies(t.id));
    n.put("quotesCount", 0L);
    n.put("isDeleted", false);
    n.put("isTweetLiked", stats.likedByUser.contains(t.id));
    n.put("isTweetRetweeted", stats.retweetedByUser.contains(t.id));
    n.put("isUserFollowByOtherUser", false);
    n.put("isTweetDeleted", false);
    n.put("isTweetBookmarked", false);
    return n;
  }

  /** Upstream's UserTweetResponse. */
  private static ObjectNode authorJson(User u) {
    ObjectNode n = Json.newObject();
    n.put("id", u.id);
    n.put("fullName", u.fullName != null ? u.fullName : u.handle);
    n.put("username", u.handle);
    n.putNull("avatar");
    n.put("isMutedDirectMessages", false);
    n.put("isMyProfileBlocked", false);
    n.put("isUserBlocked", false);
    n.put("isUserMuted", false);
    n.put("isFollower", false);
    return n;
  }

  private static ObjectNode message(String text) {
    ObjectNode node = Json.newObject();
    node.put("message", text);
    return node;
  }
}
