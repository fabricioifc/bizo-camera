export const MESSAGES = {
  account_locked: 'Conta bloqueada por 15 minutos após muitas senhas erradas.',
  invalid_code: 'Código incorreto. Confira o app autenticador e tente de novo.',
  invalid_name: 'Digite um nome de até 40 caracteres.',
  not_found: 'Esta câmera não existe mais ou não pertence à sua conta.',
  mfa_expired: 'A verificação expirou. Entre com o Google de novo.',
  too_many_requests: 'Muitas tentativas. Aguarde um minuto.',
  already_enabled: 'A verificação em duas etapas já está ativa.',
  setup_required: 'Comece a configuração antes de confirmar o código.',
  not_enabled: 'A verificação em duas etapas não está ativa.',
  invalid_credentials: 'Senha incorreta.',
  invalid_apk: 'Este arquivo não é um APK válido.',
  wrong_package: 'Este APK não é do app Monitor de Câmera (confira se é o build release).',
  too_large: 'Arquivo grande demais (máximo de 64 MB).',
  'error=google': 'Não foi possível entrar com o Google. Tente de novo.',
  'error=signup_disabled': 'O cadastro está fechado. Peça acesso ao dono do servidor.',
};

/** fetch com JSON: põe o Content-Type quando há corpo. */
export function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (options.body) headers['Content-Type'] = 'application/json';
  return fetch(path, { ...options, headers });
}

/** POST com corpo JSON. */
export function post(path, body) {
  return api(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) });
}

/** Lê o código de erro da resposta (sem consumir o corpo original). */
export async function errorCode(response) {
  const data = await response.clone().json().catch(() => ({}));
  return data.error;
}

/** Mensagem em português para uma resposta de erro do servidor. */
export async function errorText(response) {
  return MESSAGES[await errorCode(response)] || 'Não foi possível concluir a ação.';
}

/** Lança um Error com a mensagem do servidor se a resposta não for ok. */
export async function ensureOk(response) {
  if (!response.ok) throw new Error(await errorText(response));
  return response;
}
