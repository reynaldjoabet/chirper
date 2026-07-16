/** A single posted chirp. */
final case class Chirp(
    id: ChirpId,
    authorId: UserId,
    body: String,
    createdAt: java.time.Instant
) derives CanEqual

/** Identifiers are opaque so that a ChirpId cannot be passed where a UserId is
  * expected: both erase to Long, and `Chirp(authorId, id, ...)` would otherwise
  * be a silent, well-typed bug.
  *
  * The companion is what makes the type usable at all. Without `apply`, no
  * other file can construct one -- the opacity that protects the type also
  * seals it shut -- and `Chirp` itself becomes uninstantiable outside this
  * file.
  */
opaque type ChirpId = Long

object ChirpId {

  def apply(value: Long): ChirpId = value

  extension (id: ChirpId) {

    /** The underlying value, for persistence and serialisation boundaries. */
    def toLong: Long = id
  }

  // -language:strictEquality is on, so without this `a == b` on two ChirpIds does not compile.
  given CanEqual[ChirpId, ChirpId] = CanEqual.derived
}

opaque type UserId = Long

object UserId {

  def apply(value: Long): UserId = value

  extension (id: UserId) {

    /** The underlying value, for persistence and serialisation boundaries. */
    def toLong: Long = id
  }

  given CanEqual[UserId, UserId] = CanEqual.derived
}
