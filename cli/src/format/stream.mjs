// Shared helpers for following a JSONL stream from the bridge.
// Used by `jdt test status -f`, `jdt test run -f`, and the
// `jdt coverage *` -f variants.

/**
 * Follow a JSONL endpoint with SIGINT handling. Each line is
 * delivered raw to `onLine`; the caller decides whether to print
 * it as-is (--json mode) or pass it through a formatter, and may
 * accumulate per-line state.
 *
 * Returns 0 on clean exit, 1 when the stream errored before
 * SIGINT.
 */
export async function followJsonlStream(url, onLine) {
  const { getStreamLines } = await import("../client.mjs");

  let detached = false;
  const onSigint = () => {
    detached = true;
    process.stdout.write("\n");
    process.exit(0);
  };
  process.on("SIGINT", onSigint);

  try {
    await getStreamLines(url, onLine);
    return 0;
  } catch (e) {
    if (!detached) {
      console.error(e.message);
      return 1;
    }
    return 0;
  } finally {
    process.removeListener("SIGINT", onSigint);
  }
}
