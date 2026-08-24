-- V9 stored an invalid bcrypt salt, so senha123 never matched.
update users
set password_hash = '{bcrypt}$2b$10$b2lkFg.KxWPV7YcOe9RS6uN3osQztbK3JJnr6uAlTmd396myrpiia'
where email in (
    'ana.dantas@lojaexemplo.com.br',
    'fernando@lojaexemplo.com.br',
    'carla@contabilparceiro.com.br'
);
