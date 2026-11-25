SELECT crypt('password123', gen_salt('bf', 12));

INSERT INTO "users" (id, email, password, role)
SELECT '223e4567-e89b-12d3-a456-426614174006', 'testuser@test.com', '<paste the hash here>', 'ADMIN'
    WHERE NOT EXISTS (SELECT 1 FROM "users" WHERE email = 'testuser@test.com');