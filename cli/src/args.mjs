// CLI argument parsing utilities.

/**
 * Parse --flag and --key value pairs from args array.
 * Boolean flags (standalone --flag) get value `true`.
 * Key-value pairs (--key value) get the string value.
 */
export function parseFlags(args) {
  const flags = {};
  for (let i = 0; i < args.length; i++) {
    if (args[i].startsWith("--")) {
      const key = args[i].slice(2);
      if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
        flags[key] = args[++i];
      } else {
        flags[key] = true;
      }
    }
  }
  return flags;
}

/**
 * Extract positional arguments (non-flag values) from args array.
 * Skips --flag and --key value pairs.
 */
export function extractPositional(args) {
  const result = [];
  for (let i = 0; i < args.length; i++) {
    if (args[i].startsWith("--")) {
      if (i + 1 < args.length && !args[i + 1].startsWith("--")) i++;
    } else {
      result.push(args[i]);
    }
  }
  return result;
}

/**
 * Parse a Fully Qualified Method Name (FQMN) string.
 *
 * Supported formats:
 *   "pkg.Class#method(Type, Type)"  — javadoc / surefire style
 *   "pkg.Class#method"              — method without signature
 *   "pkg.Class.method(Type, Type)"  — Eclipse Copy Qualified Name style
 *   "pkg.Class"                     — plain FQN (no method)
 *
 * Returns { className, method, paramTypes } where paramTypes is:
 *   null  — no signature specified (any overload)
 *   []    — empty parens, i.e. zero-arg method
 *   ["String", "int[]", ...] — explicit parameter types
 */
export function parseFqmn(input) {
  if (!input) return { className: null, method: null, paramTypes: null };

  // Javadoc style: Class#method or Class#method(params)
  const hashIdx = input.indexOf("#");
  if (hashIdx >= 0) {
    return parseMethodPart(input.substring(0, hashIdx), input.substring(hashIdx + 1));
  }

  // Eclipse Copy Qualified Name: only when parentheses are present
  const parenIdx = input.indexOf("(");
  if (parenIdx >= 0) {
    const dotIdx = input.lastIndexOf(".", parenIdx);
    if (dotIdx >= 0) {
      return parseMethodPart(input.substring(0, dotIdx), input.substring(dotIdx + 1));
    }
  }

  // Plain FQN — no method
  return { className: input, method: null, paramTypes: null };
}

function parseMethodPart(className, rest) {
  const parenIdx = rest.indexOf("(");
  if (parenIdx < 0) {
    return { className, method: rest || null, paramTypes: null };
  }

  const method = rest.substring(0, parenIdx);
  const closeIdx = rest.lastIndexOf(")");
  const paramsStr = rest.substring(parenIdx + 1, closeIdx >= 0 ? closeIdx : rest.length);

  if (paramsStr.trim() === "") {
    return { className, method, paramTypes: [] };
  }

  return { className, method, paramTypes: splitParams(paramsStr) };
}

function splitParams(str) {
  const params = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < str.length; i++) {
    if (str[i] === "<") depth++;
    else if (str[i] === ">") depth--;
    else if (str[i] === "," && depth === 0) {
      params.push(str.substring(start, i).trim());
      start = i + 1;
    }
  }
  const last = str.substring(start).trim();
  if (last) params.push(last);
  return params.map(eraseGenerics);
}

/** Strip generics: Map<String,Integer> → Map, List<String>[] → List[] */
function eraseGenerics(type) {
  let result = "";
  let depth = 0;
  for (const ch of type) {
    if (ch === "<") depth++;
    else if (ch === ">") depth--;
    else if (depth === 0) result += ch;
  }
  return result;
}
