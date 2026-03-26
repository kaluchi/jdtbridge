# `jdt source` output brainstorm

## Core Principles

1. **Zero-Modification Navigation**: Every FQMN in the output is a valid argument for `jdt source`. Copy-paste, no editing.
2. **Badge-Link Separation**: Badges (`[M]`, `[C]`, ...) are visual prefixes, not part of the FQMN string.
3. **Full Qualification**: Never truncate packages or params. The output is the source of truth for the next command.
4. **Contextual Metadata**: Annotations like `(static)`, `→ returns [I] Type` follow the link, never break it.
5. **No Self-References**: Don't list the viewed type/method in its own refs. We're already looking at it.
6. **Byte-Exact Source**: Code inside ``` must be byte-for-byte identical to the file on disk — same tabs, line breaks, indentation. No normalization, no trimming.

## Badge Legend

```
[M] method    [C] class       [I] interface    [E] enum
[F] field     [K] constant    [A] annotation
```

---

# Example 1 — formatName (simple: project refs only)

`jdt source "com.example.client.view.task.TaskUtils.formatName(TaskGoal, boolean)"`

## V1 — Current output

#### com.example.client.view.task.TaskUtils#formatName(TaskGoal, boolean)
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

**com.example.shared.model.TaskGoal:**
`D:\git\myapp\shared\src\main\java\com\example\web\shared\core\task\goal\TaskGoal.java`

- `com.example.shared.model.TaskGoal#isSystem()` → `boolean`

**com.example.shared.model.TaskGoal.SystemGroup:**
`D:\git\myapp\shared\src\main\java\com\example\web\shared\core\task\goal\TaskGoal.java`
System groups

- `com.example.shared.model.TaskGoal.SystemGroup#findById(int)` → `com.example.shared.model.TaskGoal.SystemGroup`

**com.example.dto.core.Persistent:**
`D:\git\myapp\dao\src\main\java\com\example\dto\web\core\Persistent.java`

- `com.example.dto.core.Persistent#getId()` → `java.lang.Integer`

**com.example.client.message.I18nFactory:**
`D:\git\myapp\client\src\main\java\com\example\web\client\message\I18nFactory.java`

- `com.example.client.message.I18nFactory#getAppI18n()` → `com.example.client.message.AppI18n`
- `com.example.client.message.I18nFactory#getFunctionI18n()` → `com.example.shared.message.FunctionI18n`

**com.example.client.message.AppI18n:**
`D:\git\myapp\client\src\main\java\com\example\web\client\message\AppI18n.java`

- `com.example.client.message.AppI18n#systemGroupName(SystemGroup,boolean)` → `java.lang.String`

**com.example.shared.message.FunctionI18n:**
`D:\git\myapp\client\src\main\java\com\example\web\shared\message\FunctionI18n.java`

- `com.example.shared.message.FunctionI18n#displayName(TaskGoal)` → `java.lang.String`

### V1 Observations

What's there:
- Source with javadoc
- Refs grouped by declaring type (project scope)
- File paths (absolute), return types, javadoc first sentence

What's missing:
- No type kind — can't tell TaskGoal is a class, AppI18n is an interface, SystemGroup is an enum
- No distinction between method calls vs type references
- No interface implementations
- Return types fully qualified: `java.lang.String` instead of `String`
- No indication of static vs instance calls
- No related commands hint

## V2 — Proposed

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
*(interface methods called above → workspace implementors)*
- [M] `com.example.client.message.AppI18nImpl#systemGroupName(SystemGroup, boolean)`
- [M] `com.example.client.message.mock.MockAppI18n#systemGroupName(SystemGroup, boolean)`

Types:
- [C] `com.example.shared.model.TaskGoal` — .../TaskGoal.java
- [E] `com.example.shared.model.TaskGoal.SystemGroup` — .../TaskGoal.java
- [C] `com.example.dto.core.Persistent` — .../Persistent.java
- [C] `com.example.client.message.I18nFactory` — .../I18nFactory.java
- [I] `com.example.client.message.AppI18n` — .../AppI18n.java
- [I] `com.example.shared.message.FunctionI18n` — .../FunctionI18n.java

### V2 Observations (Example 1)

- Implementations section is hypothetical — actual `jdt impl` returns "(no implementors)" for both AppI18n and FunctionI18n (GWT generates implementations outside workspace). In real output this section would be absent.
- Return types are now simple names: `String` not `java.lang.String`, `SystemGroup` not full FQN. Less noise.
- `→ [I] AppI18n` signals: "return type is an interface" — cue for polymorphism.
- `(inherited)` on `Persistent#getId()` — getId is called on TaskGoal but declared in ancestor Persistent.
- Types section has short path hints (`.../FileName.java`) — enough to see which module without full path noise. Full path is in the FQMN itself if needed.

---

# Example 2 — handleKeyEvent (rich: same-class + project + dependency refs)

`jdt source "com.example.client.view.task.TreeNodeApi.handleKeyEvent(T, NativeEvent)"`

## V1 — Current output

#### com.example.client.view.task.TreeNodeApi#handleKeyEvent(T, NativeEvent)
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

**TreeNodeApi:**

- `com.example.client.view.task.TreeNodeApi#tryCopyName(T,NativeEvent)` → `boolean` :3593-3605

- `com.example.client.view.task.TreeNodeApi#handleAction(T,int,boolean)` → `boolean` :3451-3485

- `com.example.client.view.task.TreeNodeApi#handleCopyKey(T)` → `void` :3435-3449

- `com.example.client.view.task.TreeNodeApi#tryDeleteNode(T,int,boolean)` → `void` :3405-3410


**com.example.components.client.mvp.AbstractPresenter:**
`D:\git\myapp\client\src\main\java\com\example\components\client\mvp\AbstractPresenter.java`

- `com.example.components.client.mvp.AbstractPresenter#getModel()` → `ModelT`

**com.example.components.client.treetable.TableNodeModel:**
`D:\git\myapp\client\src\main\java\com\example\components\client\treetable\TableNodeModel.java`

- `com.example.components.client.treetable.TableNodeModel#getTableContext()` → `C`
- `com.example.components.client.treetable.TableNodeModel#getItem()` → `T`

**com.example.client.view.task.TreeNodeContext:**
`D:\git\myapp\client\src\main\java\com\example\web\client\view\task\TreeNodeContext.java`

- `com.example.client.view.task.TreeNodeContext#getSelectionModel()` → `com.example.components.client.treetable.selection.SelectionModel`

**com.example.components.client.treetable.selection.SelectionModel:**
`D:\git\myapp\client\src\main\java\com\example\components\client\treetable\selection\SelectionModel.java`

- `com.example.components.client.treetable.selection.SelectionModel#isMultiSelect()` → `boolean`

**com.example.components.client.util.UiHelper:**
`D:\git\myapp\client\src\main\java\com\example\components\client\util\UiHelper.java`

- `com.example.components.client.util.UiHelper#isCmdPressed(NativeEvent)` → `boolean`

**com.example.components.client.hotkey.KeyboardManager:**
`D:\git\myapp\client\src\main\java\com\example\components\client\hotkey\KeyboardManager.java`
Global keyboard shortcut handler.

- `com.example.components.client.hotkey.KeyboardManager#isTargetEditable(Element)` → `boolean`
  Returns true for textarea/input/div[contenteditable] without readonly attribute.

**com.example.components.client.DomHelper:**
`D:\git\myapp\client\src\main\java\com\example\components\client\DomHelper.java`

- `com.example.components.client.DomHelper#hasExpandedSelection()` → `boolean`
  Checks if there is a text selection on the page.

**com.example.components.client.treetable.SupervisingTablePresenter:**
`D:\git\myapp\client\src\main\java\com\example\components\client\treetable\SupervisingTablePresenter.java`

- `com.example.components.client.treetable.SupervisingTablePresenter#getSelectedPresenter()` → `PresenterImplT`

**com.example.client.view.task.node.TreeNodePresenter:**
`D:\git\myapp\client\src\main\java\com\example\web\client\view\task\node\TreeNodePresenter.java`

- `com.example.client.view.task.node.TreeNodePresenter#getNameBox()` → `com.example.components.client.base.EditableArea`

**com.example.shared.model.TreeItem:**
`D:\git\myapp\shared\src\main\java\com\example\web\shared\core\task\TreeItem.java`

- `com.example.shared.model.TreeItem#isStub()` → `boolean`

**References:**
- `T`
- `com.google.gwt.dom.client.NativeEvent`
- `com.google.gwt.dom.client.EventTarget`
- `com.google.gwt.dom.client.NativeEvent#getEventTarget()`
- `com.google.gwt.dom.client.Element`
- `com.google.gwt.dom.client.Element#is(JavaScriptObject)`
- `com.google.gwt.dom.client.Element#as(JavaScriptObject)`
- `com.google.gwt.dom.client.NativeEvent#getShiftKey()`
- `com.google.gwt.dom.client.NativeEvent#getKeyCode()`
- `com.google.gwt.event.dom.client.KeyCodes`
- `com.google.gwt.event.dom.client.KeyCodes#KEY_C`
- `com.google.gwt.dom.client.NativeEvent#stopPropagation()`
- `com.google.gwt.dom.client.NativeEvent#preventDefault()`
- `com.google.gwt.dom.client.NativeEvent#getAltKey()`
- `com.google.gwt.user.client.ui.UIObject#getElement()`
- `com.google.gwt.dom.client.Node#isOrHasChild(Node)`
- `com.google.gwt.event.dom.client.KeyCodes#KEY_BACKSPACE`
- `com.google.gwt.event.dom.client.KeyCodes#KEY_DELETE`

### V1 Observations

All three ref scopes visible:

**Same-class (4 refs):** Has line ranges — good for 3600+ line class. But header is just bold "TreeNodeApi:" — no type kind, no file path, no visual distinction from project refs.

**Project (9 refs, 8 types):** Chain calls `presenter.getModel().getTableContext().getSelectionModel().isMultiSelect()` split across 4 separate type sections — the chain is invisible. Generic return types (`ModelT`, `C`, `T`) are erased type params — not helpful.

**Dependency (19 refs):** Flat wall of FQMNs. Mix of types, methods, constants — no grouping. `T` type parameter leaked in as a ref. These are navigable via `jdt source` (GWT sources attached) but nothing hints at that.

**Pain points:**
1. Same-class vs project — visually identical, just bold class name
2. 19 dependency refs — wall of text, no structure
3. `@Override` in source but no info on what interface/superclass it implements
4. Chain calls invisible — each hop is a separate type section

## V2 — Proposed

[M] com.example.client.view.task.TreeNodeApi#handleKeyEvent(T, NativeEvent) @Override
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
- [M] `com.example.components.client.mvp.AbstractPresenter#getModel()` → ModelT
- [M] `com.example.components.client.treetable.TableNodeModel#getTableContext()` → C
- [M] `com.example.client.view.task.TreeNodeContext#getSelectionModel()` → SelectionModel
- [M] `com.example.components.client.treetable.selection.SelectionModel#isMultiSelect()` → boolean
- [M] `com.example.components.client.util.UiHelper#isCmdPressed(NativeEvent)` → boolean (static)
- [M] `com.example.components.client.hotkey.KeyboardManager#isTargetEditable(Element)` → boolean (static)
  Returns true for textarea/input/div[contenteditable] without readonly.
- [M] `com.example.components.client.DomHelper#hasExpandedSelection()` → boolean (static)
  Checks if there is a text selection on the page.
- [M] `com.example.components.client.treetable.SupervisingTablePresenter#getSelectedPresenter()` → PresenterImplT
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

### V2 Observations (Example 2)

Key differences from V1:

1. **Same-class is a distinct section** — no longer blends into project refs. Line numbers give orientation in the 3600-line file.

2. **Dependencies grouped by type** — 19 flat refs became 6 typed groups. NativeEvent's 6 methods, Element's 2, KeyCodes' 3 constants — scannable. `[K]` badge on constants distinguishes them from method calls.

3. **`@Override` in header** — signals this implements a contract. (TODO: resolve which interface/superclass declares `handleKeyEvent` and show it.)

4. **Javadoc inline** on `isTargetEditable` and `hasExpandedSelection` — only where it exists, directly under the ref. No separate type header needed.

5. **`T` type parameter filtered out** — was a noise ref in V1 (`- T`). Type params are not navigable.

6. **No Implementations section** — no interface method calls detected in outgoing calls (all project calls are to concrete classes). Section correctly absent.

Open questions:
- Should Dependencies section even appear by default? It's 19 refs of GWT boilerplate. Could be behind `--deps` flag or shown only when dependency count is small.
- Generic return types (`ModelT`, `C`, `PresenterImplT`) — erased params, not useful. Show them? Replace with `?`? Or resolve the actual bound?
