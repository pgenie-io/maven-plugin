-- Selects an account by its id.
select id, name
from account
where id = $id
limit 1
