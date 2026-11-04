# Upstream candidate 1 — PreferenceTest#testListPreference races its own scroll

**Target:** `packages/apps/Car/libs` —
`car-ui-lib/car-ui-lib/src/androidTest/java/com/android/car/ui/preference/PreferenceTest.java`
**Status:** PREPARED, **NOT READY TO SUBMIT AS WRITTEN** — revised 2026-08-20. Submitting to AOSP
Gerrit is an outward-facing act under Dart's name and needs a human to send it. Two things in the
draft below are now known to be wrong, and both are in the commit message:

> **1. The completeness claim must go.** The message ends `Test: atest
> PreferenceTest#testListPreference (4/4 runs)`. That was true when measured and is no longer a fair
> summary: the test **failed the gate again on 2026-08-20**, and again in a targeted loop (3 clean
> runs, 1 failure). The patch NARROWS the race; it does not close it.
>
> **2. The residual failure is at a different stage, and this report's diagnosis does not cover
> it.** A dialog-opened assertion was added immediately after the patched click. In the reproduced
> failure that assertion **passed**, the early radio-button assertions passed, and the failure was
> ~110 lines later in the *rotary focus* section — after `setInTouchMode(false)` and two
> `KEYCODE_DPAD_CENTER` presses whose comment reads "requires two inputs to focus and then click".
> Leaving touch mode does not place focus synchronously, so if focus has not landed when the first
> press arrives the second does not reopen the dialog and its radio buttons never exist. That is a
> focus-establishment race, not the scroll/click race described here.
>
> **3. The same shape existed in a sibling test and this draft did not mention it.**
> `testMultiSelectListPreference` had the identical `runOnUiThread(scrollToPreference(...))`
> followed by `onView(withText(...)).perform(click())`. Reviewers would have found it. It has since
> been patched the same way in this fork.
>
> **What still stands:** the scroll/click diagnosis itself, the reasoning about why an
> `IdlingResource` cannot fix it, and the patch. What needs rewriting before filing is the
> commit message's evidence line, and the report should say plainly that it addresses one race in a
> test that has at least two.

## Proposed commit message

```
Fix scroll/click race in PreferenceTest#testListPreference

testListPreference scrolls to a preference and then clicks it as two
separate operations:

    mActivity.runOnUiThread(() -> mActivity.scrollToPreference("list"));
    onView(withText(R.string.title_list_preference)).perform(click());

A RecyclerView scroll is an animation across frames, not a backlog of
looper messages, so Espresso considers the main thread idle while the
target row is still moving. The click can be dispatched before the row
has settled and lands on nothing: the dialog never opens, and the test
fails several assertions later with

    NoMatchingViewException: with index: <1> view.getId() is
    <car_ui_list_item_radio_button_widget>

which reads as a rendering fault rather than a timing one.

Replace the two steps with a single synchronised RecyclerView action,
which scrolls the item into view and clicks it atomically. hasDescendant
is required because the ViewHolder matcher tests the item view while
withText matches the TextView inside it.

No new dependency: espresso-contrib already resolves on the test
runtime classpath, and CarUiRecyclerViewActions already exists in this
module.

Test: atest PreferenceTest#testListPreference (4/4 runs)
Test: full car-ui-lib instrumented suite, 503 executed, no new failures
```

## Why an IdlingResource is not the fix

Worth stating because it is the obvious alternative and it does not work. A resource that samples
RecyclerView state reports idle immediately: the scroll has not STARTED when Espresso next polls,
so every condition is already true and nothing is synchronised. Arming it explicitly before the
scroll only moves the same problem to the next frame. A fixed sleep passes and is worse — it
encodes frame timing measured on one machine.

## Verification performed

- Failing before, on current AOSP `main` behaviour, reproduced on a pristine tree.
- 4/4 isolated runs pass after the change, including from a fresh build.
- Full `car-ui-lib` instrumented suite: 503 executed, failing set unchanged from baseline.
