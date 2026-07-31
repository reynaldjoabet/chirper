package auth;

import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.User;
import play.mvc.Http;
import repository.UserRepository;

/**
 * Resolves the JWT in the Authorization header to a User. Blocks on the database, so call it
 * inside work already dispatched to {@link repository.DatabaseExecutionContext}.
 */
@Singleton
public class CurrentUser {

  private final Jwt jwt;
  private final UserRepository users;

  @Inject
  public CurrentUser(Jwt jwt, UserRepository users) {
    this.jwt = jwt;
    this.users = users;
  }

  public Optional<User> resolve(Http.Request request) {
    return request.header(Http.HeaderNames.AUTHORIZATION).flatMap(jwt::verify).flatMap(users::findByEmail);
  }
}
