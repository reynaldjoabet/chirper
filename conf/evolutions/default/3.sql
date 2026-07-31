-- phase-1 auth columns for the twitter-spring-reactjs port (see PORTING.md)

-- !Ups
alter table users add column email varchar(255);
alter table users add column full_name varchar(50);
alter table users add column activation_code varchar(36);
alter table users add column password_reset_code varchar(36);
alter table users add column active boolean not null default false;
-- nullable + unique: rows from before the port have no email
alter table users add constraint uq_users_email unique (email);
-- pre-port accounts keep working
update users set active = true where password_hash is not null;

-- !Downs
alter table users drop constraint uq_users_email;
alter table users drop column active;
alter table users drop column password_reset_code;
alter table users drop column activation_code;
alter table users drop column full_name;
alter table users drop column email;
