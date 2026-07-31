package controllers.v1;

import static java.util.concurrent.CompletableFuture.supplyAsync;

import java.util.concurrent.CompletionStage;

import com.fasterxml.jackson.databind.node.ObjectNode;

import auth.CurrentUser;
import jakarta.inject.Inject;
import models.User;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import repository.DatabaseExecutionContext;
import repository.TweetRepository;
import repository.UserRepository;

/**
 * Port of upstream user-service's public read surface (PORTING.md phase 2, first slice): a user
 * profile by id, plus the sidebar's "relevant users" and (via tags) "trends". The follow graph,
 * blocking, muting and settings are later slices; those endpoints still 404 cleanly via the
 * /ui/v1/* fallback and their sidebars render empty.
 */
public class UserV1Controller extends Controller {

  private final UserRepository users;
  private final TweetRepository tweets;
  private final CurrentUser currentUser;
  private final DatabaseExecutionContext ec;

  @Inject
  public UserV1Controller(
      UserRepository users,
      TweetRepository tweets,
      CurrentUser currentUser,
      DatabaseExecutionContext ec) {
    this.users = users;
    this.tweets = tweets;
    this.currentUser = currentUser;
    this.ec = ec;
  }

  // GET /ui/v1/user/:id
  public CompletionStage<Result> profile(Long id, Http.Request request) {
    return supplyAsync(
        () ->
            users
                .findById(id)
                .map(u -> ok(profileJson(u)))
                .orElseGet(() -> notFound(message("User (id:" + id + ") not found"))),
        ec);
  }

  // GET /ui/v1/user/relevant — the "Who to follow" sidebar. Empty until the follow graph lands.
  public CompletionStage<Result> relevant(Http.Request request) {
    return supplyAsync(() -> ok(Json.newArray()), ec);
  }

  /** Upstream's UserProfileResponse, defaults for the unported follow/block/mute fields. */
  private ObjectNode profileJson(User u) {
    ObjectNode n = Json.newObject();
    n.put("id", u.id);
    n.put("fullName", u.fullName != null ? u.fullName : u.handle);
    n.put("username", u.handle);
    n.putNull("location");
    n.putNull("about");
    n.putNull("website");
    n.putNull("country");
    n.putNull("birthday");
    n.put("registrationDate", u.createdAt.toString());
    n.put("tweetCount", tweets.countByAuthor(u.id));
    n.put("mediaTweetCount", 0L);
    n.put("likeCount", 0L);
    n.put("notificationsCount", 0L);
    n.put("mentionsCount", 0L);
    n.put("active", u.active);
    n.put("profileCustomized", false);
    n.put("profileStarted", true);
    n.put("isMutedDirectMessages", false);
    n.put("isPrivateProfile", false);
    n.putNull("avatar");
    n.putNull("wallpaper");
    n.putNull("pinnedTweetId");
    n.put("followersCount", 0L);
    n.put("followingCount", 0L);
    n.put("followerRequestsCount", 0L);
    n.put("unreadMessagesCount", 0L);
    n.put("isUserMuted", false);
    n.put("isUserBlocked", false);
    n.put("isMyProfileBlocked", false);
    n.put("isWaitingForApprove", false);
    n.put("isFollower", false);
    n.put("isFollowing", false);
    n.put("isSubscriber", false);
    n.set("sameFollowers", Json.newArray());
    return n;
  }

  private static ObjectNode message(String text) {
    ObjectNode node = Json.newObject();
    node.put("message", text);
    return node;
  }
}
