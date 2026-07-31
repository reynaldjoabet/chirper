package auth;

import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import com.typesafe.config.Config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Mirrors the upstream JwtProvider contract the vendored frontend depends on: HS256, subject is
 * the email, a "role" claim, raw token in the Authorization header (no "Bearer " prefix — see
 * frontend/src/core/axios.ts). jjwt ships with Play, so no new dependency.
 */
@Singleton
public class Jwt {

  private final SecretKey key;
  private final long expirationMs;

  @Inject
  public Jwt(Config config) {
    this.key = Keys.hmacShaKeyFor(config.getString("jwt.secret").getBytes());
    this.expirationMs = config.getLong("jwt.expirationMs");
  }

  public String issue(String email) {
    return Jwts.builder()
        .subject(email)
        .claim("role", "USER")
        .expiration(new Date(System.currentTimeMillis() + expirationMs))
        .signWith(key)
        .compact();
  }

  /** The verified subject (email), or empty for a missing/garbage/expired token. */
  public Optional<String> verify(String token) {
    try {
      return Optional.of(
          Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject());
    } catch (Exception e) {
      return Optional.empty();
    }
  }
}
