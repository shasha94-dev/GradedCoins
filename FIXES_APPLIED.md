# GradedCoins fixes applied

This copy is based on commit fda2ea2dd05f73f3320f43a671671f9e3efbcc5f.

Changes:
- Preserve an existing local coin photo when saving/importing a duplicate certificate with no new photo.
- Clean up derived PCGS front/back images when removing a PCGS site image.
- Prevent switching a coin to a service/certificate pair that already exists.
- Bound scanner bitmap decoding to 2200 px to reduce memory pressure, including NGC downloaded-image scanning.

Validation:
- git diff --check: passed
- Gradle assembleDebug: not run to completion because the environment could not download Gradle 8.13 from services.gradle.org.

## Adaptive gallery image quality
- Gallery and grouped-coin cards now request local images from Coil at the actual rendered cell size.
- Pinch changes from 4/3/2/1 columns trigger a new appropriately-sized decode instead of stretching the bitmap cached for the smaller grid cell.
- Cache keys include the requested pixel size, so returning to a previous grid size can reuse the correct decoded image.
- Full-screen zoom behavior is unchanged.
