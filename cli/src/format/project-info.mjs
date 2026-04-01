// Adaptive project-info formatter.
// Adjusts detail level (packages → types → methods) to fit --lines budget.

import { toSandboxPath } from "../paths.mjs";

export function formatProjectInfo(data, maxLines) {
  const lines = [];

  // Header (always shown)
  lines.push(data.name);
  lines.push(`Location: ${toSandboxPath(data.location)}`);
  if (data.natures.length) lines.push(`Natures: ${data.natures.join(", ")}`);
  const deps = data.dependencies.length
    ? data.dependencies.join(", ")
    : "(none)";
  lines.push(`Dependencies: ${deps}`);
  lines.push(`Total: ${data.totalTypes} types`);
  lines.push("");

  const budget = maxLines - lines.length;
  if (budget <= 0) return lines.join("\n");

  // Count lines per detail tier
  const visOrder = ["public", "protected", "default", "private"];
  let typeLineCount = 0;
  const visCount = { public: 0, protected: 0, default: 0, private: 0 };

  for (const root of data.sourceRoots) {

    typeLineCount++;
    for (const pkg of root.packages) {
  
      typeLineCount++;
      for (const type of pkg.types) {
        typeLineCount++;
        const m = type.methods;
        if (m && typeof m === "object" && !Array.isArray(m)) {
          for (const vis of visOrder) {
            visCount[vis] += (m[vis] || []).length;
          }
        }
      }
    }
  }

  // Cumulative: each visibility layer adds on top
  const cumVis = {};
  let cum = typeLineCount;
  for (const vis of visOrder) {
    cum += visCount[vis];
    cumVis[vis] = cum;
  }

  // Pick richest tier that fits entirely
  let tier = "packages";
  if (data.membersIncluded) {
    for (let i = visOrder.length - 1; i >= 0; i--) {
      if (cumVis[visOrder[i]] <= budget) {
        tier = visOrder[i];
        break;
      }
    }
    if (tier === "packages" && typeLineCount <= budget) tier = "types";
  } else {
    if (typeLineCount <= budget) tier = "types";
  }

  // Which visibility groups to show
  const showVis = new Set();
  const tierVisIdx = visOrder.indexOf(tier);
  if (tierVisIdx >= 0) {
    for (let i = 0; i <= tierVisIdx; i++) showVis.add(visOrder[i]);
  }

  // Render
  let renderedPkgs = 0;
  let totalPkgs = 0;
  for (const root of data.sourceRoots) {
    totalPkgs += root.packages.length;
  }

  for (const root of data.sourceRoots) {
    lines.push(`${root.path}/  (${root.typeCount} types)`);

    for (const pkg of root.packages) {
      if (
        tier === "packages" &&
        renderedPkgs >= budget - data.sourceRoots.length
      ) {
        lines.push(`  ... and ${totalPkgs - renderedPkgs} more packages`);
        break;
      }

      if (tier === "packages") {
        lines.push(`  ${pkg.name} (${pkg.types.length})`);
        renderedPkgs++;
      } else {
        lines.push(`  ${pkg.name}`);
        for (const type of pkg.types) {
          const kindSuffix = type.kind !== "class" ? ` (${type.kind})` : "";
          const fieldsInfo = showVis.size > 0 ? `  (${type.fields}f)` : "";
          lines.push(`    ${type.name}${kindSuffix}${fieldsInfo}`);

          const m = type.methods;
          if (showVis.size > 0 && m && typeof m === "object" && !Array.isArray(m)) {
            for (const vis of visOrder) {
              if (!showVis.has(vis)) break;
              for (const method of m[vis] || []) {
                lines.push(`      ${method}`);
              }
            }
          }
        }
      }
    }
  }

  return lines.join("\n");
}
