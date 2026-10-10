# EV3 Firmware Actions

This doc gathers all the _actions_ that the EV3 firmware exposes to an Argo agent.
The file is divided in sections, each section for one specific component.

### Driving Base - Large Motors

These are the motors commonly used to make a driving robot to walk.

| Command       | Argo usage                   | Description                                                                                                               | Optional parameters & defaults                        |
| ------------- | ---------------------------- | ------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| `tool(V)`     | `.argo.act(tool(600))`       | Runs the tool indefinitely at speed V.                                                                                    | `V` optional → **500** (`ev3.velocidade.ferramenta`). |
| `toolStop`    | `.argo.act(toolStop)`        | Stops the tool, holding its position.                                                                                     | None.                                                 |
| `toolBy(G)`   | `.argo.act(toolBy(90))`      | Moves G pulses **relative** to the current position and stops on its own. Non-blocking.                                   | `G` **required**. Speed → **500**.                    |
| `toolBy(G,V)` | `.argo.act(toolBy(-45,200))` | Same relative move at speed V.                                                                                            | Both given explicitly.                                |
| `toolTo(P)`   | `.argo.act(toolTo(0))`       | Moves to **absolute** encoder position P and stops on its own. Non-blocking.                                              | `P` **required**. Speed → **500**.                    |
| `toolTo(P,V)` | `.argo.act(toolTo(90,300))`  | Same absolute move at speed V.                                                                                            | Both given explicitly.                                |
| `toolReset`   | `.argo.act(toolReset)`       | Zeroes the tool encoder: the current position becomes 0. Everything `toolTo` does afterwards is relative to this instant. | None.                                                 |

### Speaker

| Command      | Argo usage                 | Description                                                                        | Optional parameters & defaults                                                     |
| ------------ | -------------------------- | ---------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `beep`       | `.argo.act(beep)`          | Short default beep.                                                                | None. **1000 Hz** (`ev3.buzzer.frequencia`) for **200 ms** (`ev3.buzzer.duracao`). |
| `tone(F,MS)` | `.argo.act(tone(440,500))` | F hertz for MS milliseconds. Returns immediately; the driver handles the duration. | Both given explicitly.                                                             |
| `tone(F)`    | `.argo.act(tone(880))`     | F hertz **continuously**, until `buzzerOff`.                                       | `MS` omitted → continuous.                                                         |
| `buzzerOn`   | `.argo.act(buzzerOn)`      | Continuous 1000 Hz tone.                                                           | None.                                                                              |
| `buzzerOff`  | `.argo.act(buzzerOff)`     | Silence.                                                                           | None.                                                                              |

### Brick LEDs

Accepted values for **C**: `off`, `red`, `green`, `amber`, `orange`, `yellow`.

| Command       | Argo usage                   | Description                 | Optional parameters & defaults                              |
| ------------- | ---------------------------- | --------------------------- | ----------------------------------------------------------- |
| `led(C)`      | `.argo.act(led(amber))`      | Paints both sides colour C. | `C` optional → **`off`**. `led` alone turns both sides off. |
| `ledLeft(C)`  | `.argo.act(ledLeft(red))`    | Paints the left side only.  | `C` optional → **`off`**.                                   |
| `ledRight(C)` | `.argo.act(ledRight(green))` | Paints the right side only. | `C` optional → **`off`**.                                   |

### Signalling — ChonBot vocabulary

On a ChonBot these drive turn signals and a brake light. Here they are implemented using the brick LEDs.

| Command         | Argo usage                 | Description                                          | Optional parameters & defaults |
| --------------- | -------------------------- | ---------------------------------------------------- | ------------------------------ |
| `flashLOn`      | `.argo.act(flashLOn)`      | Left side amber. ChonBot intent: left turn signal.   | None.                          |
| `flashROn`      | `.argo.act(flashROn)`      | Right side amber. ChonBot intent: right turn signal. | None.                          |
| `flashLightOff` | `.argo.act(flashLightOff)` | Both sides off.                                      | None.                          |
| `breakLOn`      | `.argo.act(breakLOn)`      | Both sides red. ChonBot intent: brake light on.      | None.                          |
| `breakLOff`     | `.argo.act(breakLOff)`     | Both sides off.                                      | None.                          |

### Sensor modes

**These exist because of a hardware limitation:** a LEGO sensor measures one quantity at a time. The colour sensor in colour mode does **not** also provide reflected light — those are distinct modes of the same device, and switching costs settling time. The emitted perception changes with the mode; see [the mapping table](#which-mode-yields-which-perception).

| Command        | Argo usage                      | Description                                                                                                            | Optional parameters & defaults                                                       |
| -------------- | ------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `colorMode(M)` | `.argo.act(colorMode(reflect))` | Switches the colour sensor mode. M ∈ `color`, `reflect`, `ambient`, `rgb`.                                             | **Required**; no default. Startup mode is `color`. An unknown value returns `false`. |
| `gyroMode(M)`  | `.argo.act(gyroMode(rate))`     | Switches the gyro mode. M ∈ `angle`, `rate`, `both`.                                                                   | **Required**; no default. Startup mode is `both`.                                    |
| `gyroReset`    | `.argo.act(gyroReset)`          | Re-enters the active mode, which zeroes the accumulated angle. Equivalent to declaring "I am now facing zero degrees". | None.                                                                                |

---
