alter table users drop constraint if exists users_tenant_id_email_key;

create unique index if not exists users_email_lower_key on users (lower(email));
