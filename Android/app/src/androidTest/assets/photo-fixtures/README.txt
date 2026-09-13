Synthetic fixtures generated locally, no camera/user metadata:
- progressive.jpg: Pillow 11.3 Image.new RGB 96x48 (185,80,40), JPEG quality80 progressive=True.
- 50mp.jpg / 200mp.jpg: same solid pixels at 10000x5000 / 20000x10000, JPEG quality80.
- still.heic: Pillow + pillow-heif, same 96x48 solid image, HEIF quality80.
These fixture-generation tools are development-only, not application dependencies.
Gain-map fixture is generated at test time with Android Bitmap/Gainmap and verified to contain a gain map before normalization.
