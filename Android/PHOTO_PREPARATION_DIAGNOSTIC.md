# Photo preparation compatibility correction (after 1699d4c)

## Findings and evidence

The physical-device picker succeeds and failure occurs before upload. The old code deliberately rejected JPEG APP metadata containing `HDR`, `hdrgm`, `gainmap` or `MPF`, wide-gamut decoder output, all HEIF signatures, and images above 32 million source pixels. It also excluded palette/profile/high-bit-depth PNG. Its catch-all error concealed which check failed. These are confirmed compatibility defects; **which one rejected either physical phone's photo remains unproven** without the source or a new sanitized diagnostic trace. No source photo, private metadata or identity was collected for this change.

## Implemented pipeline

A single provider descriptor is opened and copied once into private staging. Provider MIME, filename and advertised size do not select the decoder or determine admission; actual streamed bytes do. All subsequent inspection/decoding reads that private file, so a one-shot provider need not reopen. Documents retain their existing opaque copy path and 20 MiB cap.

Allowlisted inputs: baseline/progressive JPEG (including EXIF orientation and Ultra HDR containers), still PNG supported by Android, and still HEIC/HEIF when the device's platform decoder supports it. Animated PNG and HEIF sequence brands are rejected; ImageDecoder animation and partial-image checks remain. AVIF/GIF/WebP are outside this initial allowlist. Container sniffing is followed by platform decode, not treated as proof that a file is valid.

ImageDecoder reads header dimensions before bitmap allocation, applies orientation, requests software sRGB output and a bounded target size. Ultra HDR's gain map is detached on API 34+; its SDR base is retained. Decoded pixels are drawn into a **new opaque ARGB_8888 sRGB bitmap on white**, then encoded as JPEG quality 85. Alpha is flattened. No original container, EXIF, GPS, camera model, capture timestamp, XMP or gain map is copied. Color/profile/HDR metadata alone no longer triggers rejection. HDR enhancement is intentionally not preserved; unsupported platform decoding fails cleanly, never falling back to sending the original.

Android references: [ImageDecoder target sizing and color space](https://developer.android.com/reference/android/graphics/ImageDecoder), [Ultra HDR format and SDR base](https://developer.android.com/media/platform/hdr-image-format), [Bitmap gain map API](https://developer.android.com/reference/android/graphics/Bitmap#setGainmap(android.graphics.Gainmap)). These APIs are platform-dependent; vendor decoder defects or additional working memory can still cause a safe preparation failure.

## Bounds

- Photo source: **50 MiB**, measured by bounded stream copy. This permits moderately larger camera originals without increasing network/blob limits. It is a disk staging ceiling, not permission to allocate source-size pixel buffers.
- Absolute source sanity: **256,000,000 pixels**, at most **32,768 per axis**, multiplication in Long. This admits typical 50/100/200 MP stills but rejects extreme declarations before decode.
- Decode/output: max **4096 long edge**, preserve aspect ratio, no upscaling. The requested software bitmap is at most 4096² pixels; validate returned dimensions/allocation too. Decoder output allocation is capped at eight bytes/pixel (up to 128 MiB for F16), plus a new four-byte/pixel SDR bitmap (up to 64 MiB). The input bitmap is recycled before encoding. These are bitmap bounds, not a guarantee about all native codec working memory. OOM is handled without upload; lower-memory devices may need a smaller photo.
- Encoded output: **10 MiB**, bounded during JPEG encoding; partial output is deleted on failure. There is no automatic quality-retry loop.
- Preview remains 512 maximum edge; cache/delivery/encryption architecture is unchanged.

## Error and diagnostics policy

Debug builds use only `GhostCloakPhoto` with the closed PhotoEvent/PhotoFailureReason vocabulary. Source format, size admission, dimension admission, decode, animation, HDR presence when detectable, normalization, output admission and final preparation result are categories only. HDR_UNKNOWN is used where HDR cannot be reliably classified; absence of a gain map is not claimed to prove SDR input. No source URI/path/name, exact dimensions/bytes, EXIF, identifiers, hashes, exception details or tokens are logged. Release implementation has no logging calls/tag.

Preparation failure offers **Choose another photo**. **Retry upload** requires an encrypted upload to exist. Send also rejects unprepared state independently of UI. Source limit, format/animation, bounds, read, decode, memory and output-limit errors have fixed useful messages. Selection/normalization does not invoke the upload path.

## Validation and remaining physical check

Synthetic fixtures contain only generated solid pixels: ordinary/oriented JPEG, progressive JPEG, 50 MP/200 MP JPEG, HEIF, and platform-generated gain-map JPEG. Tests verify orientation and private metadata removal, bounded dimensions/allocation/output, opaque JPEG conversion, gain-map removal, malformed/animated/bomb rejection, source limit, failed-output cleanup, unknown-size byte copying, unchanged opaque documents, and preparation/retry UI. HEIF test checks successful normalization when the local platform codec can decode, otherwise clean rejection. Release JVM policy tests invoke the no-op diagnostics without Android logging.

A synthetic emulator pass cannot establish the original phone-specific cause. On **each physical phone**, install in place without clearing data:

1. Open a conversation, **+ → Photo**, select a normal camera photo.
2. Confirm preparation and sender inline preview, then Send.
3. Confirm the recipient automatically fetches and displays the image; tap to enlarge.
4. Check orientation and absence of a new recipient Gallery/Downloads copy.
5. Repeat with a high-resolution/HDR photo (and HEIC if available).
6. If preparation fails, Android Studio Logcat filter `tag:GhostCloakPhoto`; retain only these fixed-category lines. Do not collect unfiltered logs or source metadata.

No backend deployment, database migration, account recovery, permissions or dependency changes are required.

Validation for 1f68815 (API 37 emulator): full connected suite **109 app tests and 10 storage tests, all passed**. Final focused validation adds the source-stream/output-cap cases and reruns **15 photo preparation tests plus 3 preparation-action UI tests**. 160 JVM tests (including debug/release diagnostic policy and core/attachment tests), debug/release builds and strict dependency verification pass; the IDE artifact audit verifies 557 source/Javadoc artifacts. Physical phones were not attached to that earlier session, so the two-phone retest above remains necessary.

## Follow-up: START immediately followed by NORMALIZE failure

The physical trace supplied after 1f68815 contains only `PHOTO_PREP_START` and `PHOTO_PREP_FAILED=NORMALIZE`. That label was a catch-all, **not evidence that JPEG encoding was reached**. `inspect()` emits source-size admission before container/decode work. Assuming the supplied trace is complete, the failing call precedes inspection: the initial access guard, peer eligibility, provider open/copy, or the pre-normalization access guard. The exact call on that original photo was not captured by the old diagnostics.

A concrete lifecycle ordering defect was found: MainActivity marked media and activity visibility ready after `super.onStart()`, while activity-result/lifecycle callbacks may already run during startup/resume. Photo selection invoked its access guard immediately from the result callback. Runtime foreground eligibility also belongs to the foreground lifecycle. A regression test now checks readiness at ON_START; another delivers a selection while STARTED and proves it waits until RESUMED, or is cancelled if the host is destroyed. MainActivity establishes visibility before superclass startup callbacks, and **photo** selection waits for RESUMED. Existing app-lock, account/session eligibility, epoch cancellation and background revocation checks remain required; this does not grant attachment access while locked or disconnected. The document launcher path is unchanged. The lifecycle defect is confirmed in code/tests; attributing the user's particular failure to it still requires repeating the original selection.

AndroidX [ActivityResultRegistry source](https://android.googlesource.com/platform/frameworks/support/+/e69a350c813fac7d5b11c0f5a2bd4bd2f13bc1ba/activity/activity/src/main/java/androidx/activity/result/ActivityResultRegistry.java) documents callback/pending-result delivery in lifecycle handling. Compose results are not assumed to wait for our custom foreground flags; the explicit RESUMED boundary now makes that ordering deliberate.

### Diagnostic interpretation

New fixed stages cover ACCESS_CHECK, ELIGIBILITY_CHECK, INPUT_OPEN, INPUT_COPY, BOUNDS_READ, BOUNDS_VALID, SAMPLE_CALC, BITMAP_DECODE, ORIENTATION_READ/APPLY, COLOR_CONVERT, BITMAP_CREATE, ENCODE, OUTPUT_OPEN, OUTPUT_FLUSH, OUTPUT_REOPEN and OUTPUT_VALIDATE. BOUNDS_VALID uses YES/NO; the others use OK/FAILED. The first failed stage emits `PHOTO_NORMALIZE_FAILED` with OPEN/BOUNDS/SAMPLE/DECODE/ORIENTATION/COLOR/BITMAP/ENCODE/IO/VALIDATE/OOM, plus `PHOTO_NORMALIZE_EXCEPTION` using ARGUMENT/IO/SECURITY/OOM/RUNTIME/UNKNOWN. Original exception types are categorized before the generic UI mapping. Messages/stack traces are never inspected or logged. Nested handlers do not relabel an already-reported failure.

ImageDecoder combines orientation reading/application with native decode. Successful orientation markers mean this integrated decode completed (including the normal missing-orientation default); they are not separate EXIF passes. A native failure is reported as DECODE, not speculatively blamed on EXIF. No ExifInterface is constructed from the provider stream and no second rotation is applied. Missing EXIF, oriented EXIF and malformed optional EXIF have fixtures. Software allocation and sRGB target remain explicit; a fresh software SDR bitmap flattens transparency to white and strips metadata.

Provider input is still opened exactly once and copied, without seeking/resetting or URI-to-filesystem conversion, into private staging. An unexported debug-only synthetic pipe provider now tests an unknown-size, non-seekable stream with misleading MIME. All inspection and repeated decode work uses the staged file. Copy, decode, orientation handling, normalization and preview run inside the existing Dispatchers.IO blocks. The composer explicitly shows `Preparing photo…`. Startup frame warnings alone do not establish that photo work ran on the UI thread; broader startup work was not changed.

JPEG output is flushed/synced and the stream closed, then reopened for bounded header validation: nonempty, within cap, JPEG MIME and bounded positive dimensions. Existing preview decoding follows. Any failed normalization/validation deletes partial output. No source container/EXIF/HDR enhancement is preserved, and the 50 MiB/256 MP source and 10 MiB/4096 output limits remain unchanged.

### Retest this follow-up

Update in place, filter **tag:GhostCloakPhoto**, and select the **same failing photo**. Confirm stage progression, sender preview, Send and recipient auto-fetch/inline display. If it fails, retain only these fixed-category lines: the first FAILED stage and exception category now distinguish access/provider failures from image operations. No backend deployment or database migration is required.


Follow-up validation: **162 JVM tests passed**; debug/release builds and strict dependency verification passed. **2 lifecycle tests and 18 synthetic photo tests passed on both the API 37 emulator and connected Android 16 Samsung phone**. Final emulator validation also passed **6 picker tests, 3 app-lock screen tests and all 18 photo tests**, including an asserted wide-gamut/hardware source decode followed by software SDR normalization. No original private camera photo was selected by the tests; repeat the physical steps above for the reported failure.
