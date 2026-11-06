-- Disable only the published demo account if its original shared password hash is unchanged.
-- Independently provisioned accounts and demo accounts with a changed password are preserved.
UPDATE users
SET status = 'disabled', updated_at = now()
WHERE id = 'admin-demo'
  AND email = 'admin@openimage.local'
  AND password_hash = '$2a$10$tj7W2rL.jK1bVyRA9Xsc0uNq6HWwnrYk82N8LSvHVFyYW3q3I6Fxu';
