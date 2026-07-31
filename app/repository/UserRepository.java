package repository;

import java.util.Optional;

import jakarta.inject.Singleton;
import models.User;

/**
 * Ebean-backed user store. Uniqueness of handles is enforced by the database constraint
 * (uq_users_handle), not a check-then-insert — a concurrent duplicate register loses with
 * io.ebean.DuplicateKeyException rather than slipping through a race.
 *
 * <p>Every method blocks on a connection: callers run them on {@link DatabaseExecutionContext}.
 */
@Singleton
public class UserRepository {

  /** Throws io.ebean.DuplicateKeyException when the handle is taken. */
  public User create(String handle, String passwordHash) {
    User user = new User(handle, passwordHash);
    user.save();
    return user;
  }

  public Optional<User> findById(long id) {
    return Optional.ofNullable(User.find.byId(id));
  }

  public Optional<User> findByHandle(String handle) {
    return Optional.ofNullable(User.find.query().where().eq("handle", handle).findOne());
  }

  public Optional<User> findByEmail(String email) {
    return Optional.ofNullable(User.find.query().where().eq("email", email).findOne());
  }

  public Optional<User> findByActivationCode(String code) {
    return Optional.ofNullable(User.find.query().where().eq("activationCode", code).findOne());
  }

  public Optional<User> findByPasswordResetCode(String code) {
    return Optional.ofNullable(
        User.find.query().where().eq("passwordResetCode", code).findOne());
  }
}
