# User-interface contracts

The application uses Material 3, Contako's own palette and existing original
octopus assets. Current packaged assets are the baseline; abandoned image studies
are not production masters. Proton branding retains the independent-project notice
and MUST remain replaceable. No asset ownership is reassigned by repository cleanup.

## Navigation and directory

Contacts, Groups and Sync are the primary destinations. The menu provides current
account context, Settings, About, Usage and Sign out. Do not restore a
redundant contacts-requiring-action menu: actionable problems belong in Sync.
The account address MUST share the menu items' text alignment and wrap inside a
bounded menu width, including at large font sizes. Information MUST provide a
direct link to the project's GitHub page using the system URL handler.
Information MUST separate project identity, attribution, privacy and licensing
(About) from concise features, synchronization behavior and limitations (Usage).
About and Usage MUST have separate menu entries that open their page directly,
without intermediate tabs. Back MUST return to the underlying directory context.
The About privacy section MUST link to the publicly readable
[Privacy Policy](PRIVACY_POLICY.md) using the system URL handler. This policy and
the Play Store privacy URL MUST describe the same data practices.
License details link to the maintained repository documentation; internal audit
status and duplicated field-help cards do not belong on this screen.

Keep the locale-aware alphabet fast-scroller. Do not reintroduce
the separate “go to section” control. Search and results must not overlap.
Contact rows show a readable avatar/logo, preferred secondary value and useful
pending state. Group rows use the stored Proton color and member count.

Back uses a back-arrow icon with an accessible label. Creation and destructive
actions must remain distinct. Search state and selection survive normal navigation.
Under D-138, returning from detail MUST restore the directory's visible row and
pixel offset when its contents are unchanged, including after an alphabet jump.
Contacts and Groups retain independent positions when switching tabs. Opening a
directory MUST NOT replay a previous tab reselection; a new tap on the active tab
still explicitly returns its list to the top.

Under D-136, directory search starts collapsed. The top-bar magnifier before the
account menu MUST reveal and focus the search field, opening the keyboard. Its
cross MUST clear the query, collapse the field and dismiss the keyboard, including
when the query is empty. The keyboard Search action dismisses the keyboard while
retaining results. Returning from a contact or switching tabs MUST preserve each
directory's search context without forcing the keyboard open again.

Contact rows use a 64 dp minimum and reduced text padding while preserving the
56 dp avatar and 26 dp logo. Rows MUST grow for wrapped names, additional values
and large text. Bottom navigation uses 64 dp plus system insets at ordinary font
size and retains 80 dp at font scale 1.3 or greater. Labels and touch targets MUST
remain readable and usable; the alphabet rail stays available.

## Contact and group editing

Contact detail retains the display name as its heading and shows each provided
first/family name with its own label beneath it, even if the heading is different.
These values wrap and MUST NOT be inferred by splitting the display name. Under
D-137, Save fills an empty display name from first/family names; a user-supplied
display name remains unchanged.

Present identity/media, email/groups, communication, organization/relationships,
addresses, dates, notes and advanced information progressively. Users can edit
group assignments for each email, including multiple groups on a secondary email.
Changing one occurrence MUST preserve other values and their assignments.
Creation starts with identity, email and phone controls. Other populated fields
remain available on edit; empty optional families are added through a grouped
field picker. The avatar opens photo selection. Email-group assignment remains
reachable directly from the email section. Field labels, preference and ordering
controls expand on demand; deletion remains directly available. These display
choices MUST NOT change draft contents, validation or preservation semantics.

Contact detail MUST show actual unsupported property names and values as
selectable read-only content. Whole source cards in the preservation envelope
MUST NOT be counted or rendered as additional properties. The detail screen MUST
NOT describe an otherwise idle contact as Android-compatible: pending edits,
conflicts and actionable reasons are the useful status information there.

Save shows progress, rejects repeated submission, preserves the draft on failure
and reveals invalid fields even inside collapsed sections. Unchanged dismissal
needs no warning; a real unsaved edit requires confirmation. Delete uses a named
confirmation and a busy/error state with a safe retry.

Detail actions use short labels such as Message. Primary badges align with their
value text; actions must remain usable on narrow screens and at 200% font size.
When the measured labels fit three columns, Call, Message and Email MUST occupy
one row. Pixel rounding MUST NOT push the final action onto another row; larger
text may deliberately reduce the column count to preserve legibility.
Groups use Proton's available palette and accessible foreground contrast.

## Synchronization status

Sync MUST distinguish pending local mutations from an incomplete pass with an
empty outbox. The latter displays an incomplete-synchronization message and a
retry action; it MUST NOT claim successful convergence from a zero count alone.
Specific permission, projection and other action-required reasons retain priority.

Pending Android copies MUST remain ordinary queued work until a partial-projection
failure is reported. Their presence alone MUST NOT populate the attention list or
its warning count, including during initial sync. Explicit conflicts and blocked
uploads MUST remain visible while synchronization is active.

## Images, language and accessibility

Retain preferred-image selection, gallery/LOGO distinction, fallbacks and bounded
Android image-picker decoding. Replacing an image source must not display stale
async results. HTTPS PHOTO/LOGO references are preserved but not fetched
automatically. Light/dark launcher assets and the monochrome icon remain supported.
Under D-139, contact consultation MUST hide the separate identity gallery when
there is only one image (PHOTO/LOGO combined). The header badge opens that image's
existing preview, including the unavailable-image fallback. Multiple images keep
the gallery; creation/editing retains all image controls.

Language selection shows a visual flag cue plus the language name; the text
remains authoritative and accessible. The sign-in screen stays concise: logo,
necessary fields and the independent-project notice. Do not add long introductory copy.

Check contrast, touch targets, screen-reader labels, keyboard navigation, portrait/
landscape and large text on physical devices. Adaptive layouts must account for
font scaling. Implemented UI is not proof of every accessibility/OEM configuration.
