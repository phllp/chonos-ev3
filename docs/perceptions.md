# EV3 Firmware Percepts

This doc gathers all the _perceptions_ that the EV3 firmware collects to an Argo agent.
The file is divided in sections, each section for one specific component.

All perceptions arrive in a single string separated by `;`, which Argo splits into belief-base literals. Decimal numbers use a **dot**, never a comma — `distance(78,8)` would be read by Jason as a two-term literal.

### Capabilities

| Perception | Example in a plan                  | Description             | Format & values                                                                             |
| ---------- | ---------------------------------- | ----------------------- | ------------------------------------------------------------------------------------------- |
| `has(D)`   | `+!start : has(ultrasonic) <- ...` | One per device present. | `D` ∈ `chassis`, `tool`, `ultrasonic`, `gyro`, `touch`, `color`, `led`, `buzzer`, `battery` |

These answer the question **what can this device do.** Without them, a plan written for a robot with a gripper is indistinguishable at runtime from a plan with a typo — both produce an action that does not happen.

```jason
+!start : has(ultrasonic) <- .argo.act(goAhead); +exploring.
+!start : not has(ultrasonic) <- .print("no sensor; not exploring").
```

They are constant for the lifetime of the run: the sysfs scan happens at startup, so swapping a sensor requires restarting the MAS.

### Sensors

Only devices that are physically connected are read, so the per-cycle cost
follows the actual assembly rather than the size of the vocabulary.

| Perception    | Example in a plan                           | Description                                                             | Format & range                                                             |
| ------------- | ------------------------------------------- | ----------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| `distance(D)` | `+distance(D) : D < 25 <- .argo.act(stop).` | Ultrasonic distance.                                                    | Decimal, 1 place. `0.0`–`255.0` cm. `255.0` means nothing in range.        |
| `angle(A)`    | `+angle(A) : A > 90 <- ...`                 | Accumulated gyro angle. Does not wrap at 360 — it reaches `370`, `-45`. | Signed integer, degrees.                                                   |
| `rate(R)`     | `+rate(R) : R > 200 <- ...`                 | Gyro angular velocity.                                                  | Signed integer, roughly `-440`–`440` °/s.                                  |
| `touch(S)`    | `+touch(pressed) <- ...`                    | Touch sensor state.                                                     | Atom: `pressed`, `released`.                                               |
| `color(C)`    | `+color(red) <- ...`                        | Detected colour. Colour mode only.                                      | Atom: `none`, `black`, `blue`, `green`, `yellow`, `red`, `white`, `brown`. |
| `reflect(P)`  | `+reflect(P) : P < 20 <- ...`               | Light reflected by the sensor's own LED. Reflect mode only.             | Integer `0`–`100`.                                                         |
| `ambient(P)`  | `+ambient(P) : P > 60 <- ...`               | Ambient light level. Ambient mode only.                                 | Integer `0`–`100`.                                                         |
| `rgb(R,G,B)`  | `+rgb(R,G,B) <- ...`                        | Raw red, green and blue channels. RGB mode only.                        | Three integers, `0`–`1020` each.                                           |

The ultrasonic sensor **is noisy**. Measured on the EV3 with the robot moving at
constant speed, consecutive readings ranged from 106 to 239 cm. Reacting to the
first reading below a threshold produces phantom alerts — require two
consecutive readings.
