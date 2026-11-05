'use strict';

/**
 * Roteador mínimo: cada rota é um método e um caminho exato (texto) ou uma expressão regular.
 * Os grupos da expressão chegam ao handler em ctx.params.
 */
function createRouter() {
  const routes = [];
  const add = (method) => (pattern, handler) => routes.push({ method, pattern, handler });

  return {
    get: add('GET'),
    post: add('POST'),
    patch: add('PATCH'),
    delete: add('DELETE'),

    /** Devolve { handler, params } da rota que atende a requisição, ou null. */
    match(method, pathname) {
      for (const route of routes) {
        if (route.method !== method) continue;
        if (typeof route.pattern === 'string') {
          if (route.pattern === pathname) return { handler: route.handler, params: [] };
        } else {
          const found = pathname.match(route.pattern);
          if (found) return { handler: route.handler, params: found.slice(1) };
        }
      }
      return null;
    },
  };
}

module.exports = { createRouter };
