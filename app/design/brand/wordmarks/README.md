# Approved kawaii wordmarks — September 24, 2026

The dark variant's exact edit prompt is recorded in [DARK_PROMPT.md](DARK_PROMPT.md).

Used in login/About in both themes. The wordmarks use the approved launcher
mascot as the primary identity reference: round head, large eyes, small
closed smile and compact thick arms. The original wordmark is only a layout/text
reference. Body/arms remain visible through letter openings where required.

The light and dark masters are the packaged resources:
[light](../../../src/main/res/drawable-nodpi/contako_brand_light.png) and
[dark](../../../src/main/res/drawable-nodpi/contako_brand_dark.png).
Both PNGs are unmodified RGBA outputs of the built-in image generation tool.
The backend model identifier, seed and layered/vector source are unavailable.
The approved launcher mascot is unchanged. The dark version changes lettering
colors for dark UI surfaces.
Build and device verification use the QA run template; generation is not validation.

## Prompt (verbatim)

Use case: logo-brand, character-preserving redesign.
IMAGE 1 IS THE STRICT CHARACTER IDENTITY REFERENCE: the approved kawaii baby octopus app icon. Reproduce its wide round domed head, squat plush proportions, huge round white-and-dark-indigo eyes with large white highlights, tiny centered CLOSED curved smile, gentle curved eyebrows, short thick rounded arms, lavender gradients and few broad forehead spots. This must look like exactly the same sweet baby character. No neck, no elongated head, no upright humanoid torso, no thin adult arms, no open mouth, no tongue, no mischievous grin.
IMAGE 2 IS ONLY A WORDMARK LAYOUT AND TEXT REFERENCE. Do NOT borrow its face, head proportions, pose or confused overlapping tentacles.
Make a new transparent horizontal Contako logo: the approved baby octopus hugs and intertwines with the first two letters as gently as it hugs its book in image 1. Remove the book. Keep its round head fully visible above C/o. Keep a squat rounded body directly under its head behind the first letters, and short substantial tentacles naturally attached to the lower sides of that body. Use a simple coherent pose, not an athletic stretch across the whole word.
CRITICAL OCCLUSION LOGIC: the text is a set of real solid letter-shaped objects with open counters, NOT a stencil that deletes the character. Whenever the body or an arm continues BEHIND a letter, it must REAPPEAR in that letter's opening or the gaps between letters if its physical position requires it. No mysteriously missing chunks, no amputated arm roots, no floating tips, no limbs erased by invisible rectangular masks. Draw one complete anatomically continuous octopus first in your reasoning, then place the letters in front, then only the few gripping arm tips over their rims. One arm curls softly around the top-left rim of C, continuing visibly in C's open space before disappearing only behind the actual solid stroke. Another short rounded arm goes through the first o's opening and curls over its lower-right rim: show the continuous connecting segment inside the opening. Only 2–3 small clearly traceable front/back transitions, no elaborate knots or extra detached curls. A couple of quiet short rear tentacles may support the body. Subtle local contact shading clarifies which surface is in front. Do not obscure most of the letters.
Exact main text "Contako", bold rounded sans-serif, Con deep indigo #191927, tako violet #6D4AFF. All seven letters must read clearly. Exact subtitle "Contact for Proton" on one centered line below with ample breathing room, Contact for deep indigo, Proton violet. No other text or trademark symbol.
The mascot should be adorable, soft, compact and cuddly, precisely matching image 1, while the logo stays clean and balanced. Polished soft illustration, modest gradients, no hyperreal glossy plastic. Wide composition about 2.2:1, all details inside balanced margins. TRUE transparent RGBA background, no checkerboard, rectangle, colored matte, outer glow, cast shadow or stray pixels. Single LIGHT-theme logo only.
