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
 * A registered account. The handle doubles as the display identity and as chirps.author.
 *
 * <p>passwordHash never leaves the server: there is deliberately no toJson here that includes it,
 * and controllers serialize users by hand (see AuthController#toJson).
 */
@Entity
@Table(name = "users")
public class User extends Model {

  public static final Finder<Long, User> find = new Finder<>(User.class);

  @Id public Long id;

  @Column(nullable = false, length = 15, unique = true)
  public String handle;

  @Column(name = "password_hash", nullable = false, length = 100)
  public String passwordHash;

  @WhenCreated
  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  // Phase-1 fields of the twitter-spring-reactjs port (PORTING.md). Email is the login
  // identity there; handle remains ours and doubles as the port's username.
  @Column(unique = true)
  public String email;

  @Column(name = "full_name", length = 50)
  public String fullName;

  @Column(name = "activation_code", length = 36)
  public String activationCode;

  @Column(name = "password_reset_code", length = 36)
  public String passwordResetCode;

  @Column(nullable = false)
  public boolean active;

  public User() {}

  public User(String handle, String passwordHash) {
    this.handle = handle;
    this.passwordHash = passwordHash;
  }

  // Mutations live on the entity on purpose: only models.* is Ebean-enhanced, so a public-field
  // write from a controller never marks the property dirty and save() silently updates nothing.
  // Field writes here are intercepted. (Observed live: a password set from a controller no-oped.)

  public void updateRegistration(String email, String fullName) {
    this.email = email;
    this.fullName = fullName;
    if (this.passwordHash == null) {
      this.passwordHash = ""; // set for real at registration/confirm
    }
    save();
  }

  public void newActivationCode(String code) {
    this.activationCode = code;
    save();
  }

  public void activate() {
    this.activationCode = null;
    this.active = true;
    save();
  }

  public void changePassword(String hash) {
    this.passwordHash = hash;
    this.active = true;
    this.passwordResetCode = null;
    save();
  }

  public void newPasswordResetCode(String code) {
    this.passwordResetCode = code;
    save();
  }
}
