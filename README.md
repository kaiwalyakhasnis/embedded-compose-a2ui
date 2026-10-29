# embedded-compose-a2ui

An [A2UI](https://a2ui.org) v0.9 renderer for
[embedded-compose](https://github.com/kaiwalyakhasnis/embedded-compose):
an agent (a language model, a server, any program) describes a screen in
A2UI's JSON messages, and this library draws it on a device with no
Android, as Material 3 components (embedded-compose's `core/material`).

Package `ui.a2ui`, Kotlin/Native (`linuxArm64`), no other dependencies: it
has its own small JSON parser.

## What it does

- **`A2uiClient`** takes the server-to-client messages: `createSurface`,
  `updateComponents`, `updateDataModel`, `deleteSurface`. Each surface keeps
  its components and data model as Compose snapshot state, so the screen
  follows every update, including a stream of partial ones. Messages it
  can't take get an `error` reply (`VALIDATION_FAILED`).
- **`A2uiView`** draws a surface with the basic catalog:

  | A2UI | drawn as (Material 3) |
  |---|---|
  | `Text` (`h1`-`h5`, `body`, `caption`) | Material's type scale, Roboto |
  | `Icon` | Material icon names (and common aliases) |
  | `Row`, `Column`, `List` (templates too) | rows and columns |
  | `Card` | filled card |
  | `Divider` | divider |
  | `Button` (`primary`, `borderless`, default) | filled, text, tonal button |
  | `CheckBox` | checkbox with its label |
  | `ChoicePicker` | filter chips (one or many) |
  | `Slider` | Material's slider, with - and + buttons |
  | `Tabs`, `Modal` | tabs; a dialog-like surface opened in place |
  | `TextField`, `DateTimeInput` | filled text field, shown, not edited |
  | `Image`, `Video`, `AudioPlayer` | placeholders (nothing is downloaded) |

  A surface's `theme.primaryColor` seeds its color scheme.
- **Data binding**: `{"path": ...}` (relative inside list templates),
  two-way for the controls; functions `formatString`, `formatNumber`,
  `formatCurrency`, `formatDate`, `pluralize`, `required`, `email`,
  `regex`, `length`, `numeric`, `and`, `or`, `not`, `now`; button
  `checks`.
- **Actions**: a pressed button gives an `Action` (its event name and
  resolved context); `Action.message()` is the client-to-server message,
  with the data model when the surface asked for it (`sendDataModel`).
- **Keys instead of touch**: `FocusScroll` scrolls a tall screen to the
  focused control. With `FocusScrollState.key` as the device's key filter,
  the focus key first reads through the text a page at a time, then moves
  to the controls; `ScrollIndicator` shows where you are.

## Using it

It builds inside an embedded-compose checkout, as a library of an app kept
outside that repo. In the app's `app.env`:

```sh
APP_LIBS="a2ui=../../embedded-compose-a2ui"   # path relative to the app's directory
```

and in its `build.gradle.kts`:

```kotlin
implementation(project(":libs:a2ui"))
```

Then `APP_DIR=<the app> ./ec build <board>` (or `./ec binary <board>`).
The app also needs embedded-compose's Roboto fonts (`APP_FONTS`, see
`core/material`).

```kotlin
val client = A2uiClient { action, surface ->
    send(action.message(if (surface.sendDataModel) surface.data else null).encode())
}
// for each message from the agent (null, or an error message to send back):
client.handle(Json.parse(line))
// on the screen:
for (s in client.surfaces) A2uiView(client, s)
```

## Used by

[nexus5x-local-llm](https://github.com/kaiwalyakhasnis/nexus5x-local-llm)'s
`a2ui` screen: a language model on a Nexus 5X answers spoken questions as
A2UI screens.
