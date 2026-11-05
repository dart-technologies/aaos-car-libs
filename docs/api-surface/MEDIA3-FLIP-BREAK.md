# The Media3 flip's break to the published surface

Mechanically derived, not summarised from memory: `scripts/check-api-surface.py` was run against the
AAR built at `73647113e^` (the commit before the flip) and against the AAR at HEAD, and the two
surfaces diffed. Only types a consumer outside the library can name are included — package-private
and anonymous inner classes are implementation detail and are filtered out.

## Verdict: an intentional break, and a major-version one

The 2026-09-07 Copilot review classified the helper signature as a blocker. That disposition is
rejected: this document is the deliberate major-version decision record, so no legacy signature is
reintroduced. Public Media3 and Guava types are now exported transitively, and a consumer-only
compile fixture depends solely on `car-media-common` to prevent publication metadata from hiding a
missing dependency.

Eight public or protected members were REMOVED. Six of them mention `MediaBrowserCompat`,
`MediaControllerCompat` or `MediaSessionCompat.Token` in their signature, which is the point of the
flip: the deprecated client cannot leave the implementation while remaining in the API. There is no
source- or binary-compatible way to do this, and a deprecate-then-remove cycle would mean keeping a
second legacy connection alive purely to service the old signatures.

So this is deliberate. It is recorded here rather than discovered by a consumer.

## Removed

    public static boolean getSupportsSearch(MediaBrowserCompat)          -> now (Bundle)
    public static int     getRootBrowsableHint(MediaBrowserCompat)       -> now (Bundle)
    public static int     getRootPlayableHint(MediaBrowserCompat)        -> now (Bundle)
    public  PlaybackViewModel(Application, LiveData<BrowsingState>, PlaybackViewModel$InputFactory)
    protected MediaBrowserCompat createMediaBrowser(MediaSource, MediaBrowserCompat$ConnectionCallback)
    public  MediaSessionCompat$Token BrowsingState.getSessionToken()
    public  BrowsingState(MediaSource, MediaBrowserCompat, ConnectionStatus)
    public final MediaBrowserCompat BrowsingState.mBrowser

Three of these are worth calling out because they break something other than a compile:

  - `createMediaBrowser` is **protected**, so it is API to a SUBCLASS, not just a caller. Anyone who
    overrode it to inject a browser now overrides nothing, and their override is silently unused
    rather than failing to compile — the worst failure mode in this list.
  - `BrowsingState.mBrowser` is a public FIELD. Field access is resolved at link time, so a consumer
    compiled against the old AAR fails at runtime with NoSuchFieldError rather than at build time.
  - `PlaybackViewModel`'s three-argument constructor is gone entirely: `InputFactory` existed only to
    build a `MediaControllerCompat` from a browser's token, and in Media3 `MediaBrowser extends
    MediaController`, so there is no second client to construct.

## Added (non-breaking)

`MediaErrorCodes` (13 wire constants), `Media3PlaybackState` (`toLegacyState`, `toLegacyActions`,
`toPlaybackState`), `Media3Adapters.fromMedia3QueueItem`, and the Media3-typed replacements for the
removed members.

## What now prevents the next one from being silent

`scripts/check-api-surface.py` reads the surface out of the built AAR — the artifact a consumer
actually receives — and compares it to the baselines in this directory. A change is not an error; an
UNRECORDED change is. Accepting one means running `--record` and committing the baseline next to the
code that caused it, which puts the decision in a diff a reviewer sees.

The checker is verified two ways: it reproduces this entire before/after from two real builds, and a
deliberately corrupted baseline makes it return rc=1 naming the offending member.

---

## Stage 4 addendum, 2026-08-18: MediaItemMetadata

The gate built in this directory caught its first real change, which was mine, one commit after it
was written.

**Removed**

    public MediaItemMetadata(MediaDescriptionCompat, Long, boolean, boolean, String, String)

**Added**

    public final class MediaItemMetadata$Builder   (+ 11 setters and build())

`MediaItemMetadata` stored a `MediaDescriptionCompat` and every accessor read it, so a deprecated
type sat in the artwork loader's path and in the parcel. It now holds the fields directly. The
deprecated type survives only in the signatures of the three legacy convenience constructors, where
it is unavoidable — those constructors exist to accept it, and `Media3AdaptersTest` uses them as the
legacy side of its equivalence assertions, so removing them would dismantle the reference the Media3
conversion is measured against.

The constructor was removed rather than deprecated because keeping it would keep the deprecated type
reachable from every caller, which is what stage 4 exists to end — and because `Media3Adapters` was
*constructing* a `MediaDescriptionCompat` out of Media3 metadata purely to satisfy it: building a
deprecated object on the modern path. A builder rather than an eleven-argument constructor, since
eleven positional arguments including four consecutive booleans and strings is a call site where two
can be swapped in silence.

**A change this gate cannot see.** The parcel format changed with it — from one marshalled
`MediaDescriptionCompat` to the fields written by hand. `javap` pins signatures, and a parcel format
is neither a signature nor anything a compiler checks, so nothing in `docs/api-surface/` would show
this. It was always an ad-hoc format with no version tag, so it could never safely cross two builds
of this library; that has not become true, it has become visible. `MediaItemMetadataTest` covers it
instead — and had to be rewritten twice before it did, see the commit.
