/**
 * HTTP proxy support for Docker sandbox environments.
 *
 * Docker sandbox routes traffic through http_proxy. Node.js
 * http.request doesn't support this natively. This module
 * builds request options that route through the proxy when
 * http_proxy is set and host is not in no_proxy.
 */

/**
 * Build http.request options, routing through HTTP proxy if needed.
 * @param {string} hostname
 * @param {number} port
 * @param {string} path
 * @param {string} method
 * @param {number} timeoutMs
 * @param {Object} [extraHeaders]
 * @returns {Object} options for http.request
 */
export function proxyAwareOptions(hostname, port, path, method, timeoutMs, extraHeaders) {
  const proxy = process.env.http_proxy || process.env.HTTP_PROXY;
  const noProxy = (process.env.no_proxy || process.env.NO_PROXY || "")
    .split(",").map(s => s.trim());
  const useProxy = proxy && !noProxy.includes(hostname);

  if (useProxy) {
    const proxyUrl = new URL(proxy);
    return {
      hostname: proxyUrl.hostname,
      port: parseInt(proxyUrl.port, 10),
      path: `http://${hostname}:${port}${path}`,
      method,
      timeout: timeoutMs,
      headers: { ...extraHeaders, Host: `${hostname}:${port}` },
    };
  }
  return {
    hostname,
    port,
    path,
    method,
    timeout: timeoutMs,
    headers: extraHeaders || {},
  };
}
