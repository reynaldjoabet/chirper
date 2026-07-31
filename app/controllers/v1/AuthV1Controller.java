package controllers.v1;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.supplyAsync;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.favre.lib.crypto.bcrypt.BCrypt;
import auth.Jwt;
import jakarta.inject.Inject;
import models.User;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import repository.DatabaseExecutionContext;
import repository.UserRepository;

/**
 * Port of upstream user-service AuthenticationController + RegistrationController
 * (twitter-spring-reactjs, see PORTING.md). Paths, request bodies, response shapes and message
 * strings follow upstream so the vendored frontend works unmodified.
 *
 * <p>Registration is upstream's three-step flow: check (creates an inactive account), code
 * ("emails" an activation code — logged here until an email provider exists), activate, then
 * confirm (sets the password and returns {user, token}). Login answers the same envelope. The
 * token is a raw HS256 JWT the frontend stores and replays in the Authorization header.
 */
public class AuthV1Controller extends Controller {

  private static final Logger log = LoggerFactory.getLogger(AuthV1Controller.class);

  private static final int BCRYPT_COST = 12;

  private final UserRepository users;
  private final Jwt jwt;
  private final DatabaseExecutionContext ec;

  @Inject
  public AuthV1Controller(UserRepository users, Jwt jwt, DatabaseExecutionContext ec) {
    this.users = users;
    this.jwt = jwt;
    this.ec = ec;
  }

  // POST /ui/v1/auth/registration/check
  public CompletionStage<Result> registrationCheck(Http.Request request) {
    JsonNode json = request.body().asJson();
    if (json == null) {
      return completedFuture(badRequest(message("expected a JSON body")));
    }
    String email = json.path("email").asText("").trim().toLowerCase();
    String username = json.path("username").asText("").trim();
    if (email.isEmpty() || username.isEmpty()) {
      return completedFuture(badRequest(message("Email and username must be filled")));
    }
    return supplyAsync(
        () -> {
          Optional<User> existing = users.findByEmail(email);
          if (existing.isPresent() && existing.get().active) {
            return forbidden(message("Email has already been taken."));
          }
          User user = existing.orElseGet(User::new);
          boolean isNew = user.id == null;
          if (user.handle == null) {
            // The frontend sends the display name as username; the handle must be unique, so
            // it gets a discriminator when taken. Upstream does the same at confirm time.
            String base = username.replaceAll("[^A-Za-z0-9_]", "_");
            base = base.substring(0, Math.min(base.length(), 15));
            String candidate = base.isEmpty() ? "user" : base;
            while (users.findByHandle(candidate).isPresent()) {
              String suffix = Integer.toString((int) (Math.random() * 10000));
              candidate = base.substring(0, Math.min(base.length(), 15 - suffix.length())) + suffix;
            }
            user.handle = candidate;
          }
          if (isNew) {
            user.email = email;
            user.fullName = username;
            user.passwordHash = "";
            user.save(); // insert: full state persists without dirty tracking
          } else {
            user.updateRegistration(email, username);
          }
          return ok(message("User data checked."));
        },
        ec);
  }

  // POST /ui/v1/auth/registration/code
  public CompletionStage<Result> registrationCode(Http.Request request) {
    return withEmailUser(
        request,
        user -> {
          user.newActivationCode(UUID.randomUUID().toString());
          // Stands in for upstream's email-service until a provider is configured. Printed to
          // stdout as well: the slf4j binding currently NOPs in dev (scribe/logback conflict).
          System.out.println("[email-stub] activation code for " + user.email + ": " + user.activationCode);
          log.info("[email-stub] activation code for {}: {}", user.email, user.activationCode);
          return ok(message("Registration code sent successfully"));
        });
  }

  // GET /ui/v1/auth/registration/activate/:code
  public CompletionStage<Result> registrationActivate(String code) {
    return supplyAsync(
        () ->
            users
                .findByActivationCode(code)
                .map(
                    user -> {
                      user.activate();
                      return ok(message("User successfully activated."));
                    })
                .orElseGet(() -> notFound(message("Activation code not found."))),
        ec);
  }

  // POST /ui/v1/auth/registration/confirm
  public CompletionStage<Result> registrationConfirm(Http.Request request) {
    JsonNode json = request.body().asJson();
    if (json == null) {
      return completedFuture(badRequest(message("expected a JSON body")));
    }
    String email = json.path("email").asText("").trim().toLowerCase();
    String password = json.path("password").asText("");
    if (password.length() < 8) {
      return completedFuture(badRequest(message("Your password needs to be at least 8 characters")));
    }
    return supplyAsync(
        () ->
            users
                .findByEmail(email)
                .map(
                    user -> {
                      user.changePassword(
                          BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray()));
                      log.info("user @{} completed registration", user.handle);
                      return ok(authResponse(user));
                    })
                .orElseGet(() -> notFound(message("User not found"))),
        ec);
  }

  // POST /ui/v1/auth/login
  public CompletionStage<Result> login(Http.Request request) {
    JsonNode json = request.body().asJson();
    if (json == null) {
      return completedFuture(badRequest(message("expected a JSON body")));
    }
    String email = json.path("email").asText("").trim().toLowerCase();
    String password = json.path("password").asText("");
    return supplyAsync(
        () -> {
          Optional<User> user = users.findByEmail(email);
          boolean ok =
              user.isPresent()
                  && !user.get().passwordHash.isEmpty()
                  && BCrypt.verifyer()
                      .verify(password.toCharArray(), user.get().passwordHash)
                      .verified;
          // Upstream's message for both unknown email and bad password on this endpoint.
          return ok
              ? ok(authResponse(user.get()))
              : notFound(message("Incorrect password or email"));
        },
        ec);
  }

  // GET /ui/v1/user/token — the frontend calls this on boot with the stored JWT to restore state.
  public CompletionStage<Result> token(Http.Request request) {
    Optional<String> email =
        request.header(Http.HeaderNames.AUTHORIZATION).flatMap(jwt::verify);
    if (email.isEmpty()) {
      return completedFuture(unauthorized(message("JWT expired or invalid")));
    }
    return supplyAsync(
        () ->
            users
                .findByEmail(email.get())
                .map(user -> ok(authResponse(user)))
                .orElseGet(() -> notFound(message("User not found"))),
        ec);
  }

  // POST /ui/v1/auth/forgot/email
  public CompletionStage<Result> forgotEmail(Http.Request request) {
    return withEmailUser(request, user -> ok(message("Reset password code is send to your E-mail")));
  }

  // POST /ui/v1/auth/forgot
  public CompletionStage<Result> forgot(Http.Request request) {
    return withEmailUser(
        request,
        user -> {
          user.newPasswordResetCode(UUID.randomUUID().toString());
          System.out.println("[email-stub] reset code for " + user.email + ": " + user.passwordResetCode);
          return ok(message("Reset password code is send to your E-mail"));
        });
  }

  // GET /ui/v1/auth/reset/:code
  public CompletionStage<Result> resetCode(String code) {
    return supplyAsync(
        () ->
            users
                .findByPasswordResetCode(code)
                .map(user -> ok(userJson(user)))
                .orElseGet(() -> notFound(message("Password reset code is invalid!"))),
        ec);
  }

  // POST /ui/v1/auth/reset
  public CompletionStage<Result> reset(Http.Request request) {
    JsonNode json = request.body().asJson();
    if (json == null) {
      return completedFuture(badRequest(message("expected a JSON body")));
    }
    String email = json.path("email").asText("").trim().toLowerCase();
    String password = json.path("password").asText("");
    String password2 = json.path("password2").asText("");
    if (!password.equals(password2)) {
      return completedFuture(badRequest(message("Passwords do not match.")));
    }
    if (password.length() < 8) {
      return completedFuture(badRequest(message("Your password needs to be at least 8 characters")));
    }
    return supplyAsync(
        () ->
            users
                .findByEmail(email)
                .map(
                    user -> {
                      user.changePassword(
                          BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray()));
                      return ok(message("Password successfully changed!"));
                    })
                .orElseGet(() -> notFound(message("User not found"))),
        ec);
  }

  private CompletionStage<Result> withEmailUser(
      Http.Request request, java.util.function.Function<User, Result> action) {
    JsonNode json = request.body().asJson();
    if (json == null) {
      return completedFuture(badRequest(message("expected a JSON body")));
    }
    String email = json.path("email").asText("").trim().toLowerCase();
    return supplyAsync(
        () ->
            users
                .findByEmail(email)
                .map(action)
                .orElseGet(() -> notFound(message("Email not found"))),
        ec);
  }

  private ObjectNode authResponse(User user) {
    ObjectNode node = Json.newObject();
    node.set("user", userJson(user));
    node.put("token", jwt.issue(user.email));
    return node;
  }

  /**
   * Upstream AuthUserResponse: every field the frontend's AuthUser type reads, with honest
   * defaults for the ones later phases will fill (counts 0, flags false, media null).
   */
  private static ObjectNode userJson(User u) {
    ObjectNode n = Json.newObject();
    n.put("id", u.id);
    n.put("email", u.email);
    n.put("fullName", u.fullName != null ? u.fullName : u.handle);
    n.put("username", u.handle);
    n.putNull("location");
    n.putNull("about");
    n.putNull("website");
    n.putNull("countryCode");
    n.putNull("country");
    n.putNull("phoneCode");
    n.putNull("phoneNumber");
    n.putNull("gender");
    n.putNull("language");
    n.putNull("birthday");
    n.put("registrationDate", u.createdAt.toString());
    n.put("tweetCount", 0L);
    n.put("mediaTweetCount", 0L);
    n.put("likeCount", 0L);
    n.put("notificationsCount", 0L);
    n.put("mentionsCount", 0L);
    n.put("active", u.active);
    n.put("profileCustomized", false);
    n.put("profileStarted", true);
    n.put("mutedDirectMessages", false);
    n.put("privateProfile", false);
    n.put("backgroundColor", "DEFAULT");
    n.put("colorScheme", "BLUE");
    n.putNull("avatar");
    n.putNull("wallpaper");
    n.putNull("pinnedTweetId");
    n.put("followersCount", 0L);
    n.put("followingCount", 0L);
    n.put("followerRequestsCount", 0L);
    n.put("unreadMessagesCount", 0L);
    n.put("isMutedDirectMessages", false);
    n.put("isPrivateProfile", false);
    return n;
  }

  private static ObjectNode message(String text) {
    ObjectNode node = Json.newObject();
    node.put("message", text);
    return node;
  }
}
