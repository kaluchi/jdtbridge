/**
 * Bridge environment — single source of truth for pinned
 * connection config from env vars.
 *
 * Encapsulates all process.env reads for bridge config.
 * Mockable in tests via vi.doMock("../src/bridge-env.mjs").
 */

/** @returns {{ port: number, token: string, host: string, session: string } | null} */
export function getPinnedBridge() {
  const port = process.env.JDT_BRIDGE_PORT;
  const token = process.env.JDT_BRIDGE_TOKEN;
  if (!port || !token) return null;
  return {
    port: Number(port),
    token,
    host: process.env.JDT_BRIDGE_HOST || "127.0.0.1",
    session: process.env.JDT_BRIDGE_SESSION || "",
  };
}
