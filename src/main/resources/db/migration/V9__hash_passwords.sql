update users
set password_hash = '{bcrypt}$2b$10$5AaUYeQ0knbEr9cBDqW8z.1pbqc6ECpA07INAGQNw9IjDO0NAgLiO'
where password_hash like '{noop}%';
