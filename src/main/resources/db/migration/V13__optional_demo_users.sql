DELETE FROM users
WHERE '${seed_demo}' <> 'true'
  AND email IN (
    'ana.dantas@lojaexemplo.com.br',
    'fernando@lojaexemplo.com.br',
    'carla@contabilparceiro.com.br'
  );
