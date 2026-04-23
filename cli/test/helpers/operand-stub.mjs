// Generic stub factory for qlang builtin operands.
//
// stubOp(name, qlangSource) → { [name]: <stub-impl> }
//
// Parses qlangSource via evalQuery, wraps the resulting value in an
// overloadedOp that ignores captured args (covers 0- and 1-captured
// shapes — enough for axis-style operands like @incomingRefs that
// take an optional modifier). The single-entry object is shaped to
// spread directly into a loader's implsOverrides Map, so the operand
// name appears only once at the call site:
//
//   implsOverrides: await stubOp("@incomingRefs", `[...]`)

import { evalQuery } from "@kaluchi/qlang-core";
import { overloadedOp } from "@kaluchi/qlang-core/dispatch";

export async function stubOp(name, qlangSource) {
  const value = await evalQuery(qlangSource);
  return {
    [name]: overloadedOp(name, 2, {
      0: () => value,
      1: async () => value,
    }),
  };
}
