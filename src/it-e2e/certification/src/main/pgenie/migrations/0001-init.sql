create table account (
  id int8 not null generated always as identity primary key,
  name text not null
);
