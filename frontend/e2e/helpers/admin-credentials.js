export function adminPassword() {
  const password = process.env.E2E_ADMIN_PASSWORD;
  if (!password) throw new Error('Set E2E_ADMIN_PASSWORD to the test administrator password');
  return password;
}
