# Watch face format (`.wtheme`, schema 1)

A `.wtheme` file is a ZIP containing `manifest.json` plus any images/fonts it references. Data only: no code.
Limits: 30 MB, 600 files, no `..` paths.

```
mytheme.wtheme
├── manifest.json
├── bg.webp            (any image; down-sampled and center-cropped to the screen)
└── fonts/MyFont.ttf   (optional)
```

## Coordinates
- `x`, `cx` = fraction of screen **width** (0..1); `y`, `cy` = fraction of **height**.
- `size`, `radius`, `w`, `h`, `width` = fraction of the **short side**. This is how one theme fits every screen.
- `anchor`: `center`, `topLeft`, `topCenter`, `topRight`, `centerLeft`, `centerRight`, `bottomLeft`, `bottomCenter`, `bottomRight`.

## manifest.json
```json
{
  "schema": 1,
  "id": "my_theme",
  "name": { "en": "My Theme", "ar": "ثيمي" },
  "accent": "#FFFF9F0A",
  "background": { "type": "image", "src": "bg.webp", "scrim": 0.2, "overlay": "twinkle" },
  "elements": [
    { "type": "digitalTime", "x": 0.5, "y": 0.45, "size": 0.3, "font": "tajawal_bold", "format": "auto", "seconds": false },
    { "type": "text", "text": "{date:EEEE d MMMM}", "x": 0.5, "y": 0.62, "size": 0.07, "color": "#CCFFFFFF" },
    { "type": "batteryChip", "x": 0.05, "y": 0.05, "anchor": "topLeft", "w": 0.3, "h": 0.11 }
  ],
  "aod": { "elements": [ { "type": "digitalTime", "x": 0.5, "y": 0.5, "size": 0.25, "style": "outline" } ] }
}
```

### background
| type | fields |
|---|---|
| `image` | `src` (`bg.webp` inside the theme, or `asset:wallpapers/<name>.webp` for built-in wallpapers) |
| `solid` | `colors: ["#FF000000"]` |
| `gradient` | `colors: [top, bottom, …]` |
| `procedural` | `procedural`: `liquid`, `starfield`, `neongrid`, `waves`, `bubbles`, `nightdrive`, `racer`, `speedo`; `colors`, `speed` |

Optional: `overlay` (`twinkle`, `bubbles`, `starfield`) with `overlayColor`, and `scrim` (0..0.9 darkening).
Built-in wallpapers: flow, liquid*, dusk, carbon, aurora, dunes, geo, mountains, nebula, mesh, silk, sunset_car, tail_lights, track, bokeh, ember.

### elements
| type | main fields |
|---|---|
| `digitalTime` | `format` (`auto`, `HH:mm`, `h:mm`, `HH`, `mm`), `font`, `color`, `style` (`fill`/`outline`), `ghost` (e.g. `"88:88"` LCD effect), `seconds`, `tracking`, `glow`, `shadow{r,dx,dy,color}`, `digits` (`auto`/`latin`/`arabicIndic`) |
| `text` | `text` with placeholders, `font`, `color`, `upper` |
| `analog` | `cx`,`cy`,`radius`, `style` (`classic`,`sword`,`line`,`baton`), `hourColor`,`minuteColor`,`secondColor`,`centerColor`, `hourW`,`minuteW`,`secondW`, `seconds`, `smooth` |
| `ticks` | `cx`,`cy`,`radius`,`count`,`major`,`minorLen`,`majorLen`,`minorW`,`majorW`,`color`,`majorColor`,`style` (`line`/`dot`),`numerals` (`none`/`quarters`/`all`),`numeralSize`,`numeralInset` |
| `ring` | `cx`,`cy`,`radius`,`width`,`source` (`steps`,`battery`,`seconds`,`minute`,`hour`,`day`),`color`,`trackColor`,`start` |
| `batteryChip` | `x`,`y`,`w`,`h`,`anchor` |
| `panel` | rounded rectangle: `x`,`y`,`w`,`h`,`color`,`corner` |
| `dot` | `text: "notif"` = dot shown only when there are notifications |
| `gauge` | interactive circular complication: `cx`,`cy`,`radius`,`source` (`steps`,`battery`,`phone`,`day`),`color`,`trackColor`,`backdrop`,`numeralColor` |

### Complications (interactive, can be switched on/off)
Elements showing steps, battery, prayer, Hijri, weather, phone battery or the notification dot automatically become
**complications** (or set `"slot": "steps"` etc. explicitly). The user turns each one on/off per theme
(long-press the face → palette → Complications), and **tapping** one opens its details (battery → Control Center,
notifications → Notification Center, the rest → Widgets). Add `"defaultOff": true` to ship one switched off.

Colors: `#AARRGGBB`, `#RRGGBB`, `@accent` (user/theme accent), `@time` (user time color).
Built-in fonts: `tajawal_light`, `tajawal_medium`, `tajawal_bold`, `tajawal_extrabold`, `bebas`, `michroma`, `techmono`, `ruqaa` (or a `.ttf` path inside the theme).

### placeholders
`{time} {hour} {minute} {second} {ampm} {date:PATTERN} {weekday} {day} {month} {year} {battery} {steps} {stepsGoal}
{hijri} {hijriShort} {prayer.next} {prayer.nextTime} {prayer.countdown} {phone.battery} {weather.temp} {weather.desc} {notif.count}`
