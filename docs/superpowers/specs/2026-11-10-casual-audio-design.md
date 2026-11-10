# Rust EQ casual audio design

The existing native Android music player should feel approachable and make sound settings understandable at a glance. Keep every existing playback, PEQ, preset, import/export, AutoEQ, spatial, external-session and diagnostic operation available.

Design direction: a small, friendly personal sound mixer. Use cloud #F3F6FC for the app background, white #FFFFFF for editing surfaces, ink #202B47 for text, blue #4056CE for primary actions, orange #A74B17 for bass and green #246D58 for spatial audio. Pale tints provide grouping; text never relies on a pale accent for contrast. Android sans-serif medium/rounded-feeling headings and regular Japanese body text use a 12/14/16/20/28 sp scale, with 48 dp minimum interactive controls.

The subject-specific signature is the sound curve: large editable PEQ on the sound page, compact representative curves on presets, and stereo-position illustrations in the space page. Illustrations derive from actual settings where possible; static preset thumbnails are illustrative, not an output measurement. No simulated live analyzer, stock images, decorative gradients or continuously running animation.

Layout (left-aligned, content centered at a maximum 840 dp on tablets):

    Rust EQ                         EQ on/off
    [ personal player / open music / play ]
    [ sound curve / presets / bass controls ]
    [ Sound           Space           Tools ]

Space and Tools replace the scrollable page body. The navigation stays available. Each page remembers its scroll position. Advanced band editing is expandable. All 16 factory presets are available in a labeled, scrollable chooser; common presets appear directly on the sound page. Imported/user presets remain available with save/delete. File pickers continue to use SAF. Icons use native vector paths and have accessibility labels; switches and sliders name the setting they change.

Alternatives considered: recoloring the existing long stack would keep navigation cumbersome; a rotary hardware-console treatment would obscure precise touch editing. The selected design uses a clear curve, direct presets and three destinations. User has explicitly requested autonomous improvement without confirmation, so implementation proceeds in the current shared workspace.

Verify navigation, factory-preset selection and retained settings across pages with an Android instrumentation test. Build both ABIs, run Android Lint and the existing tests, then inspect emulator screenshots at narrow, normal, large-font and tablet sizes. Existing review findings unrelated to this UI work remain separate follow-up work.
