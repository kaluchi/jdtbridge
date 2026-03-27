# `jdt source` output design

## Core Principles

1. **Server Exhaustive, Client Formats**: The plugin computes ALL metadata exhaustively — for every reference: FQMN, type kind, file path, line range (start-end), javadoc, modifiers, resolved bounds, override targets, implementors. The CLI decides how to render it. Server never omits data; client experiments with presentation.

2. **Deterministic Output**: The format is a contract. Every section either appears (when data exists) or doesn't (when data is empty). Never "collapsed", never "summarized", never behind a flag. The tool is predictable.

3. **Zero-Modification Navigation**: Every FQMN in the output is a valid argument for `jdt source`. Copy-paste, no editing.

4. **Badge-Link Separation**: Badges (`[M]`, `[C]`, ...) are visual prefixes, not part of the FQMN string.

5. **Full Qualification**: Never truncate packages or params in FQMN links. The output is the source of truth for the next command.
   Exception: within type-level method/field index, short form `#name()` is used — the FQN prefix is the viewed type.

6. **Contextual Metadata**: Annotations like `(static)`, `(inherited)`, `→ ReturnType` follow the link, never break it.

7. **No Self-References**: Don't list the viewed type/method in its own refs.

8. **Byte-Exact Source**: Code inside ``` must be byte-for-byte identical to the file on disk — same tabs, line breaks, indentation. No normalization, no trimming.

9. **Source Order**: Outgoing calls and same-class refs listed in source-appearance order.

10. **Resolve Type Parameter Bounds**: Generic return types (`ModelT`, `C`) resolved to upper bound. Show `→ TableNodeModel (bound)`. When bound is `Object` → show `→ ?`.

11. **Resolve @Override**: When method has `@Override`, resolve and show the declaring supertype/interface as a navigable FQMN.

12. **Flat Calls**: No chain-call nesting. Chains are visible through source order — consecutive calls = likely chain.

13. **Each Command Has One Job**: `jdt source` = source code + external relationships. `jdt type-info` = compact structural overview. Don't mix them. If the model worries about output size, it can `jdt source Foo | wc -l` first, or use `jdt type-info` instead.

14. **Simple Return Type Names**: `String` not `java.lang.String`, `SystemGroup` not full FQN. Return type badge `[I]`/`[C]` when it disambiguates (e.g. `→ [I] AppI18n` signals return type is an interface).

## Badge Legend

```
[M] method    [C] class       [I] interface    [E] enum
[F] field     [K] constant    [A] annotation
```

---

# Server JSON Contract

The plugin returns a flat structure: source + flat array of refs. The client does all grouping, ordering, formatting.

## Top-level response

```json
{
  "fqmn":      "pkg.Class#method(Param)",
  "file":      "D:\\...\\Class.java",
  "startLine": 42,
  "endLine":   55,
  "source":    "..byte-exact source..",
  "overrideTarget": "pkg.Interface#method(Param)",   // null if no @Override
  "refs": [ ...ref objects... ]
}
```

## Ref object — the server contract

Every ref in the `refs` array carries all metadata. Client never needs a second query.

```json
{
  "fqmn":           "pkg.Other#doStuff(String)",  // navigable FQMN
  "direction":      "outgoing",                    // outgoing | incoming
  "kind":           "method",                      // method | field | type | constant
  "typeKind":       "class",                       // class | interface | enum | annotation
                                                   //   — kind of the DECLARING type
  "scope":          "project",                     // class | project | dependency
  "file":           "D:\\...\\Other.java",         // absolute path (null for dependency)
  "line":           100,                           // start line (null if no source)
  "endLine":        115,                           // end line (null or omit if == line)
  "type":           "Customer",                     // return type resolved at CALL SITE
                                                   //   via IMethodBinding.getReturnType()
                                                   //   not from declaration
  "returnTypeFqn":  "pkg.model.Customer",          // full FQN of return type (navigable)
  "returnTypeKind": "class",                       // kind of the return type
  "typeBound":      "Customer",                    // fallback when call-site resolution fails
                                                   //   (raw types, unresolvable type params)
  "static":         false,                         // static modifier
  "inherited":      true,                          // declared in ancestor, called on subtype
  "inheritedFrom":  "pkg.Ancestor",                // declaring type FQN if inherited
  "implementationOf": null,                         // FQMN of interface method this implements
                                                    //   (null unless this is an impl ref)
  "doc":            "First sentence of javadoc."   // javadoc summary (null if none)
}
```

**Direction:**
- `outgoing` — this method calls/references the ref (AST visitor over method body)
- `incoming` — the ref calls/references this method (SearchEngine workspace query)

**Implementation resolution** (outgoing interface method calls):
- For each outgoing ref where `typeKind: "interface"` and `kind: "method"`, the server resolves all implementations via `IType.newTypeHierarchy(null)` → `getAllSubtypes()`
- Each implementation is a separate ref in the flat list with `implementationOf` pointing to the interface FQMN
- Scope: **workspace + classpath** — everything JDT can resolve. Includes library-internal implementations (Eclipse, Spring, GWT, etc.) — needed for debugging library code from stack traces
- JDK interfaces (`java.*`) already filtered out by `isJdkType` — never in outgoing refs, never resolved
- No cardinality caps — all found implementations returned

Example — outgoing call to interface method + its implementations:
```json
{ "fqmn": "pkg.AppI18n#systemGroupName(SystemGroup,boolean)",
  "direction": "outgoing", "kind": "method", "typeKind": "interface", ... }

{ "fqmn": "pkg.AppI18nImpl#systemGroupName(SystemGroup,boolean)",
  "direction": "outgoing", "kind": "method", "typeKind": "class",
  "implementationOf": "pkg.AppI18n#systemGroupName(SystemGroup,boolean)", ... }

{ "fqmn": "pkg.mock.MockAppI18n#systemGroupName(SystemGroup,boolean)",
  "direction": "outgoing", "kind": "method", "typeKind": "class",
  "implementationOf": "pkg.AppI18n#systemGroupName(SystemGroup,boolean)", ... }
```

### Field notes

- **`typeKind`** — the kind of the type that DECLARES this member. Enables `[C]`/`[I]`/`[E]`/`[A]` badges. For `kind: "type"` refs, this IS the type's kind.
- **`type`** — return type resolved at **call site** via `IMethodBinding.getReturnType()`, NOT from declaration. `Repository<Customer>.load()` → type: `Customer`, not `T`. This is how "Open Return Type" works in Eclipse.
- **`returnTypeFqn`** — full FQN of the return type, navigable via `jdt source`. Enables "Open Return Type" navigation.
- **`returnTypeKind`** — enables `→ [I] AppI18n` badge on return type. Null for void/primitive.
- **`typeBound`** — fallback when call-site resolution fails (raw types, unresolvable type params). Shows upper bound. Null when type is concrete or bound is Object.
- **`inherited`** + **`inheritedFrom`** — `getId()` called on TaskGoal but declared in Persistent. Client shows `(inherited)`.
- **`static`** — client shows `(static)` annotation.
- **`scope`** — client uses this for grouping: `class` → Same-class, `project` → Workspace, `dependency` → Libraries. Server determines scope by `IType.isBinary()`.
- **`implementationOf`** — links an implementation ref back to the interface method it implements. Client uses this to group implementations under the interface call. Null for non-implementation refs.
- **`overrideTarget`** — on the top-level response, not per-ref. The viewed method's override source.
- **`doc`** — server collects for ALL scopes (including dependency when source attached). Client decides whether to show.

### What the server does NOT do

- No grouping — flat list, client groups by scope/declaring type
- No ordering — refs in AST visit order (≈ source order), client can re-sort
- No filtering — all refs included, client decides what to show/hide
- No formatting — raw data, client renders markdown/badges/annotations

### Current vs needed (delta)

| Field | Current | Needed | JDT API |
|---|---|---|---|
| fqmn | yes | yes | — |
| kind | yes | yes | — |
| scope | yes | yes | — |
| file | project only | all scopes | `IClassFile.getPath()` for deps |
| type | yes | yes | — |
| line/endLine | yes | yes | — |
| doc | class+project | all scopes | remove scope filter |
| **typeKind** | **no** | **yes** | `IType.isClass/isInterface/isEnum/isAnnotation` |
| **returnTypeFqn** | **no** | **yes** | `IMethodBinding.getReturnType().getQualifiedName()` — call-site resolved |
| **returnTypeKind** | **no** | **yes** | resolve return type → `IType` → same checks |
| **static** | **no** | **yes** | `Flags.isStatic(element.getFlags())` |
| **inherited** | **no** | **yes** | compare `mb.getDeclaringClass()` vs receiver |
| **inheritedFrom** | **no** | **yes** | declaring type FQN when inherited |
| **typeBound** | **no** | **yes** | `ITypeBinding.getTypeBounds()` |
| **overrideTarget** | **no** | **yes** | `IMethodBinding` → walk supertypes |
| **direction** | **no** | **yes** | outgoing = AST visitor, incoming = SearchEngine |
| **implementationOf** | **no** | **yes** | `IType.newTypeHierarchy()` → `getAllSubtypes()` for interface method calls |

---

# Method-level Output

## Sections (in order)

| # | Section | When present |
|---|---|---|
| 1 | **Header** — badge + FQMN | Always |
| 2 | **Override** — resolved declaring type | When `@Override` |
| 3 | **Location** — file path : line range | Always |
| 4 | **Source** — code block | Always |
| 5 | **Same-class** — calls to same-class members | When exists |
| 6 | **Outgoing Calls** — project-scope calls | When exists |
| 7 | **Implementations** — implementors of interface methods from Outgoing Calls | When interface calls exist and workspace has implementors |
| 8 | **Dependencies** — external library calls, grouped by declaring type | When exists |
| 9 | **Types** — project types referenced | When exists |

## Example: formatName (simple — project refs only)

`jdt source "com.example.client.view.task.TaskUtils.formatName(TaskGoal, boolean)"`

---

[M] com.example.client.view.task.TaskUtils#formatName(TaskGoal, boolean)
`D:\git\myapp\client\src\main\java\com\example\web\client\view\task\TaskUtils.java:542-555`

```java
	/**
	 * System groups
	 *
	 * @param goalStandard			- goal
	 * @param currentUserStandard   - whether the goal belongs to the current user
	 * @return formatted goal name
	 */
	public static String formatName(TaskGoal goalStandard, boolean currentUserStandard) {
		if (goalStandard.isSystem()) {
			SystemGroup group = SystemGroup.findById(goalStandard.getId());
			return I18nFactory.getAppI18n().systemGroupName(group, currentUserStandard);
		}
		return I18nFactory.getFunctionI18n().displayName(goalStandard);
	}
```

Outgoing Calls:
- [M] `com.example.shared.model.TaskGoal#isSystem()` → boolean
- [M] `com.example.shared.model.TaskGoal.SystemGroup#findById(int)` → SystemGroup (static)
- [M] `com.example.dto.core.Persistent#getId()` → Integer (inherited)
- [M] `com.example.client.message.I18nFactory#getAppI18n()` → [I] AppI18n (static)
- [M] `com.example.client.message.I18nFactory#getFunctionI18n()` → [I] FunctionI18n (static)
- [M] `com.example.client.message.AppI18n#systemGroupName(SystemGroup, boolean)` → String (interface call)
- [M] `com.example.shared.message.FunctionI18n#displayName(TaskGoal)` → String (interface call)

Implementations:
- [M] `com.example.client.message.AppI18nImpl#systemGroupName(SystemGroup, boolean)`
- [M] `com.example.client.message.mock.MockAppI18n#systemGroupName(SystemGroup, boolean)`

Types:
- [C] `com.example.shared.model.TaskGoal` — .../TaskGoal.java
- [E] `com.example.shared.model.TaskGoal.SystemGroup` — .../TaskGoal.java
- [C] `com.example.dto.core.Persistent` — .../Persistent.java
- [C] `com.example.client.message.I18nFactory` — .../I18nFactory.java
- [I] `com.example.client.message.AppI18n` — .../AppI18n.java
- [I] `com.example.shared.message.FunctionI18n` — .../FunctionI18n.java

---

### Notes (formatName)

- No Same-class section — no self-class calls
- No Dependencies section — all refs are project-scope
- Implementations hypothetical for this example: GWT generates implementations outside workspace; in practice section absent if no workspace implementors found
- `(inherited)` on `Persistent#getId()` — called on TaskGoal but declared in ancestor
- `→ [I] AppI18n` — return type badge signals interface = polymorphism cue


## Example: handleKeyEvent (rich — same-class + project + dependency)

`jdt source "com.example.client.view.task.TreeNodeApi.handleKeyEvent(T, NativeEvent)"`

---

[M] com.example.client.view.task.TreeNodeApi#handleKeyEvent(T, NativeEvent)
    overrides [I] `com.example.components.client.view.KeyEventHandler#handleKeyEvent(T, NativeEvent)`
`D:\git\myapp\client\src\main\java\com\example\web\client\view\task\TreeNodeApi.java:3376-3403`

```java
	@Override
	public boolean handleKeyEvent(T presenter, NativeEvent nativeEvent) {
		final EventTarget eventTarget = nativeEvent.getEventTarget();
		final boolean multiSelect = presenter.getModel().getTableContext().getSelectionModel().isMultiSelect();
		if (!multiSelect && Element.is(eventTarget)) {
			final Element target = Element.as(eventTarget);
			final boolean isMeta = UiHelper.isCmdPressed(nativeEvent);
			final boolean shiftKey = nativeEvent.getShiftKey();
			final int keyCode = nativeEvent.getKeyCode();
			if (keyCode == KeyCodes.KEY_C && isMeta && !KeyboardManager.isTargetEditable(target) && !DomHelper.hasExpandedSelection()) {
				return tryCopyName(presenter, nativeEvent);
			} else if (!KeyboardManager.isTargetEditable(target) && !shiftKey && handleAction(presenter, keyCode, isMeta)) {
				nativeEvent.stopPropagation();
				nativeEvent.preventDefault();
				return true;
			} else if (!KeyboardManager.isTargetEditable(target) && keyCode == KeyCodes.KEY_C && nativeEvent.getAltKey()) {
				handleCopyKey(presenter);
			} else if (KeyboardManager.isTargetEditable(target)
					&& presenter.getSelectedPresenter().getNameBox().getElement().isOrHasChild(target)
					&& !presenter.getSelectedPresenter().getModel().getItem().isStub()) {
				// handle some hotkeys during editing
				if (keyCode == KeyCodes.KEY_BACKSPACE || keyCode == KeyCodes.KEY_DELETE) {
					tryDeleteNode(presenter, keyCode, isMeta);
				}
			}
		}
		return false;
	}
```

Same-class:
- [M] `com.example.client.view.task.TreeNodeApi#tryCopyName(T, NativeEvent)` → boolean :3593
- [M] `com.example.client.view.task.TreeNodeApi#handleAction(T, int, boolean)` → boolean :3451
- [M] `com.example.client.view.task.TreeNodeApi#handleCopyKey(T)` → void :3435
- [M] `com.example.client.view.task.TreeNodeApi#tryDeleteNode(T, int, boolean)` → void :3405

Outgoing Calls:
- [M] `com.example.components.client.mvp.AbstractPresenter#getModel()` → TableNodeModel (bound)
- [M] `com.example.components.client.treetable.TableNodeModel#getTableContext()` → TreeNodeContext (bound)
- [M] `com.example.client.view.task.TreeNodeContext#getSelectionModel()` → SelectionModel
- [M] `com.example.components.client.treetable.selection.SelectionModel#isMultiSelect()` → boolean
- [M] `com.example.components.client.util.UiHelper#isCmdPressed(NativeEvent)` → boolean (static)
- [M] `com.example.components.client.hotkey.KeyboardManager#isTargetEditable(Element)` → boolean (static)
  Returns true for textarea/input/div[contenteditable] without readonly.
- [M] `com.example.components.client.DomHelper#hasExpandedSelection()` → boolean (static)
  Checks if there is a text selection on the page.
- [M] `com.example.components.client.treetable.SupervisingTablePresenter#getSelectedPresenter()` → TreeNodePresenter (bound)
- [M] `com.example.client.view.task.node.TreeNodePresenter#getNameBox()` → EditableArea
- [M] `com.example.shared.model.TreeItem#isStub()` → boolean

Dependencies:
  [C] `com.google.gwt.dom.client.NativeEvent`
  - [M] `com.google.gwt.dom.client.NativeEvent#getEventTarget()` → EventTarget
  - [M] `com.google.gwt.dom.client.NativeEvent#getShiftKey()` → boolean
  - [M] `com.google.gwt.dom.client.NativeEvent#getKeyCode()` → int
  - [M] `com.google.gwt.dom.client.NativeEvent#stopPropagation()`
  - [M] `com.google.gwt.dom.client.NativeEvent#preventDefault()`
  - [M] `com.google.gwt.dom.client.NativeEvent#getAltKey()` → boolean
  [C] `com.google.gwt.dom.client.Element`
  - [M] `com.google.gwt.dom.client.Element#is(JavaScriptObject)` → boolean (static)
  - [M] `com.google.gwt.dom.client.Element#as(JavaScriptObject)` → Element (static)
  [C] `com.google.gwt.event.dom.client.KeyCodes`
  - [K] `com.google.gwt.event.dom.client.KeyCodes#KEY_C`
  - [K] `com.google.gwt.event.dom.client.KeyCodes#KEY_BACKSPACE`
  - [K] `com.google.gwt.event.dom.client.KeyCodes#KEY_DELETE`
  [C] `com.google.gwt.dom.client.EventTarget`
  [C] `com.google.gwt.user.client.ui.UIObject`
  - [M] `com.google.gwt.user.client.ui.UIObject#getElement()` → Element
  [C] `com.google.gwt.dom.client.Node`
  - [M] `com.google.gwt.dom.client.Node#isOrHasChild(Node)` → boolean

Types:
- [C] `com.example.components.client.mvp.AbstractPresenter` — .../AbstractPresenter.java
- [C] `com.example.components.client.treetable.TableNodeModel` — .../TableNodeModel.java
- [C] `com.example.client.view.task.TreeNodeContext` — .../TreeNodeContext.java
- [C] `com.example.components.client.treetable.selection.SelectionModel` — .../SelectionModel.java
- [C] `com.example.components.client.util.UiHelper` — .../UiHelper.java
- [C] `com.example.components.client.hotkey.KeyboardManager` — .../KeyboardManager.java
- [C] `com.example.components.client.DomHelper` — .../DomHelper.java
- [C] `com.example.components.client.treetable.SupervisingTablePresenter` — .../SupervisingTablePresenter.java
- [C] `com.example.client.view.task.node.TreeNodePresenter` — .../TreeNodePresenter.java
- [C] `com.example.shared.model.TreeItem` — .../TreeItem.java

---

### Notes (handleKeyEvent)

- `overrides` line resolves `@Override` → navigable FQMN to the interface contract
- Type param bounds resolved: `→ TableNodeModel (bound)` instead of `→ ModelT`
- Dependencies: all 19 refs shown, grouped by declaring type. No collapsing, no summary — every ref is a navigable FQMN
- `T` type parameter filtered out (not navigable)
- No Implementations section — no interface method calls detected
- Source order preserved: first 4 outgoing calls trace the `.getModel().getTableContext().getSelectionModel().isMultiSelect()` chain naturally

---

# Type-level Output

When `jdt source` receives an FQN (no `#method`), the ``` block contains the **full source of the type** — same principle as method-level: byte-exact window into the file, with `file:from-to`.

Fields, methods, nested types, constants — all visible in the source. No need to duplicate them as separate indices.

Sections after source contain only what is **NOT in the source code** — relationships the compiler knows but the file text doesn't show: subtypes, implementors, enclosing type. Supertypes are in the `extends`/`implements` clause but not as resolved FQMNs — so Hierarchy section resolves them.

## Sections (in order)

| # | Section | When present |
|---|---|---|
| 1 | **Header** — badge + FQN | Always |
| 2 | **Location** — file path : line range | Always |
| 3 | **Source** — full type source, byte-exact | Always |
| 4 | **Hierarchy** — supertypes ↑ (resolved FQMNs) and subtypes ↓ | When has supertypes (beyond Object) or subtypes |
| 5 | **Implementors** — workspace classes implementing this interface | Interface only, when exists |
| 6 | **Enclosing Type** — link to parent | When nested/inner type |

## Example: Class

`jdt source "com.example.shared.model.TaskGoal"`

---

[C] com.example.shared.model.TaskGoal
`D:\git\myapp\shared\src\main\java\com\example\web\shared\core\task\goal\TaskGoal.java:1-95`

```java
package com.example.web.shared.core.task.goal;

import com.example.dto.web.core.Persistent;
import java.io.Serializable;

/**
 * Represents a task goal with optional system group classification.
 */
public class TaskGoal extends Persistent implements Serializable {

	private static final int MAX_LENGTH = 255;

	private String name;
	private boolean systemFlag;

	public boolean isSystem() {
		return systemFlag;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String formatName(boolean currentUser) {
		if (isSystem()) {
			SystemGroup group = SystemGroup.findById(getId());
			// ...
		}
		return name;
	}

	/**
	 * System groups
	 */
	public enum SystemGroup {
		INBOX, TODAY, UPCOMING, SOMEDAY, LOGBOOK;

		public static SystemGroup findById(int id) {
			return values()[id];
		}

		public int getDisplayOrder() {
			return ordinal();
		}
	}
}
```

Hierarchy:
  ↑ [C] `com.example.dto.core.Persistent`
  ↑ [I] `java.io.Serializable`
  ↓ [C] `com.example.shared.model.TaskGoalImpl`
  ↓ [C] `com.example.test.model.MockTaskGoal`

---

### Notes (Class)

- Source is the **entire class** including package, imports, inner classes — byte-exact, with `file:1-95`
- No Fields/Methods/Nested Types indices — all visible in source
- Hierarchy resolves FQMNs that source text doesn't: `extends Persistent` → `com.example.dto.core.Persistent`; subtypes not visible in source at all

## Example: Interface

`jdt source "com.example.client.message.AppI18n"`

---

[I] com.example.client.message.AppI18n
`D:\git\myapp\client\src\main\java\com\example\web\client\message\AppI18n.java:1-18`

```java
package com.example.web.client.message;

import com.example.web.shared.core.task.goal.TaskGoal.SystemGroup;
import com.example.web.shared.core.task.goal.TaskGoal;
import com.example.web.shared.core.ErrorCode;

/**
 * Application-level i18n interface for user-facing labels.
 */
public interface AppI18n {

	String systemGroupName(SystemGroup group, boolean currentUser);

	String taskLabel(TaskGoal goal);

	String errorMessage(ErrorCode code);
}
```

Implementors:
- [C] `com.example.client.message.AppI18nImpl`
- [C] `com.example.client.message.mock.MockAppI18n`

---

### Notes (Interface)

- **Implementors** — not visible in source, only the compiler knows who implements this
- No Hierarchy section needed here — interface doesn't extend anything. If it did, ↑ arrows would show.

## Example: Enum (nested)

`jdt source "com.example.shared.model.TaskGoal.SystemGroup"`

---

[E] com.example.shared.model.TaskGoal.SystemGroup
`D:\git\myapp\shared\src\main\java\com\example\web\shared\core\task\goal\TaskGoal.java:42-56`

```java
	/**
	 * System groups
	 */
	public enum SystemGroup {
		INBOX, TODAY, UPCOMING, SOMEDAY, LOGBOOK;

		public static SystemGroup findById(int id) {
			return values()[id];
		}

		public int getDisplayOrder() {
			return ordinal();
		}
	}
```

Enclosing Type:
- [C] `com.example.shared.model.TaskGoal`

---

### Notes (Enum)

- Source window is the **enum's range within the file**, not the whole file — `TaskGoal.java:42-56`
- **Enclosing Type** — the compiler knows the nesting, the source window doesn't include the parent class context
- Constants, methods — all in source, no separate index

## Example: Annotation

`jdt source "com.example.shared.annotation.Audited"`

---

[A] com.example.shared.annotation.Audited
`D:\git\myapp\shared\src\main\java\com\example\web\shared\annotation\Audited.java:1-20`

```java
package com.example.web.shared.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Marks entities that require audit logging on mutation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Audited {

	String value() default "";

	AuditLevel level() default AuditLevel.STANDARD;
}
```

---

### Notes (Annotation)

- No sections after source — retention, target, members all visible in source code
- No hierarchy (annotations don't extend), no implementors

---

# Open Questions

1. **Package-level** — `jdt source "com.example.shared.model"` could show: types in package, package-info.java javadoc. Low priority but natural extension.

2. **Inner class: source range** — nested enum example shows `TaskGoal.java:42-56` (the inner type range). Top-level class shows `TaskGoal.java:1-95` (the whole file). For inner classes, should enclosing class source also be included, or just the inner type? Current design: just the inner type + Enclosing Type link.

3. **Inner class navigation** — When viewing an inner class, enclosing type is linked. What about sibling inner classes? Show "Also nested in TaskGoal: [E] SystemGroup, [C] Builder"?
