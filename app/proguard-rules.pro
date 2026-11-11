# Release builds are shrunk (unused code and resources are removed) but not renamed: DocNest's
# code is public, so hiding names gains nothing, and a crash report still names the real classes
# and functions.
-dontobfuscate

# Shrinking renumbers lines. Keeping these lets `retrace` turn a crash report's line numbers back
# into the real ones, using app/build/outputs/mapping/release/mapping.txt from the same build.
-keepattributes SourceFile,LineNumberTable
