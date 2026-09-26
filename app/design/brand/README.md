# Shared Contako artwork — September 24, 2026

The final [intertwined light/dark wordmarks and their prompts](WORDMARKS.md)
are documented separately from the launcher mascot below.

The runtime master is `../../src/main/res/drawable-nodpi/contako_mascot_v2.png`:
1254 × 1254 RGBA, SHA-256
`D1CC2C70589DD66AE42C05FE35BC105DC9457033559183C4E4C1FB3BCB439094`.
This is the unmodified transparent output of OpenAI's built-in image generation
tool, using the preceding launcher and light wordmark as references. The tool
did not disclose a backend model identifier, seed or layered/vector source.
Transparency includes fully transparent background pixels and antialiased edges.

The login/About wordmarks keep the octopus intertwined with "Contako" and the
exact subtitle "Contact for Proton". `ContakoBrand.kt` selects the transparent
light/dark wordmark from the app's actual surface color, including manual theme
overrides. The logo scales as a graphic with one accessible description. The login
container remains 152 dp high; the unofficial-project notice remains separate.
The launcher retains its 20% fractional inset, neutral light/dark backgrounds and
existing monochrome vector. The drawing MUST NOT be enlarged to fill adaptive masks.

Previous raster wordmarks are preserved under `previous/`, outside packaged
resources. The previous foreground is retained under
`../launcher/ast-003-previous-runtime.png`. Proton and creator marks are unchanged.
The mascot and wordmarks remain raster masters, so this work MUST NOT be described
as delivery of D-071's fully editable vector illustration or lettering.
The preview page compares themes and masks; it is not an Android screenshot.

## Generation prompt (verbatim)

Use case: logo-brand. Redraw and polish Contako's existing octopus/contact-book mascot for an Android app, with a genuinely transparent RGBA background, not a checkerboard painted into the image. Image 1 is the principal composition reference; image 2 is a secondary character reference only (do NOT include its lettering). Preserve the friendly purple octopus embracing a contacts address book, friendly eyes and a gentle smile. One isolated mascot, absolutely no letters or words, no Proton logo, no padlock, no rounded-square background. Improve clarity and consistency: broad clean lavender shapes, restrained soft gradients, dark indigo eyes with white highlights rather than yellow eyes, fewer fine details and clearly separated rounded tentacles, readable at 48 pixels. The book cover should be pale lavender/near-white with a simple purple person silhouette and a discreet blue tab so it separates from the purple body. Refined warm friendly illustrated app identity, not glossy plastic, not photorealistic, not excessively cute or childish. Balanced front-facing compact composition, entire head, tentacles and book visible, centered, no cropping. Square 1024x1024 canvas if possible with generous even transparent space around the complete illustration (about 12% each edge). True transparent background outside silhouette and in negative spaces; no colored matte, halo, cast shadow or detached elements. Crisp anti-aliased edges suitable for both almost-white and dark-indigo UI backgrounds.
