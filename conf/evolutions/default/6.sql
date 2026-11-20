-- indexes matched to the queries the repositories actually run
--
-- Portable on purpose, like 1-5: this runs on H2 (dev, tests) and Postgres (production), so no
-- partial indexes, INCLUDE columns or other Postgres-only syntax. Each new index is created before
-- the one it replaces, so no query is left without an index mid-migration.

-- !Ups

-- TweetRepository.byAuthor: author_id = ? and reply_to_id is null order by id desc, paged.
-- On (author_id) alone Postgres reads ALL of an author's tweets and sorts them per page. On
-- (author_id, id) it walks the author's slice newest-first and stops after one page. The
-- (author_id) prefix still serves countByAuthor and fk_tweets_author.
-- H2 binds a foreign key to the index it found at creation and refuses to drop that index, so
-- the FK is dropped and re-added around the swap. Re-adding it re-validates tweets.author_id,
-- which is one scan at this table size.
create index tweets_author_id_idx on tweets(author_id, id);
alter table tweets drop constraint fk_tweets_author;
drop index tweets_author_idx;
alter table tweets add constraint fk_tweets_author foreign key (author_id) references users (id);

-- ChirpRepository.list(Optional author): author = ? order by id desc. Same reasoning.
create index chirps_author_id_idx on chirps(author, id);
drop index chirps_author_idx;

-- UserRepository.findByActivationCode / findByPasswordResetCode had no index: every account
-- activation and password reset was a full scan of users. Unique as well as fast: findOne()
-- throws on a duplicate, and two accounts sharing a reset code would be a security bug. Both
-- columns are nullable, and NULLs never collide in a unique index (Postgres and H2 alike).
create unique index uq_users_activation_code on users(activation_code);
create unique index uq_users_password_reset_code on users(password_reset_code);

-- !Downs
drop index uq_users_password_reset_code;
drop index uq_users_activation_code;
create index chirps_author_idx on chirps(author);
drop index chirps_author_id_idx;
create index tweets_author_idx on tweets(author_id);
alter table tweets drop constraint fk_tweets_author;
drop index tweets_author_id_idx;
alter table tweets add constraint fk_tweets_author foreign key (author_id) references users (id);
