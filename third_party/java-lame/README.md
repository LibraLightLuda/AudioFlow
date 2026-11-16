# Java LAME 3.98.4

- Upstream: https://github.com/nwaldispuehl/java-lame/releases/tag/v3.98.4
- Source tag commit: `0224bc4` (full original source is included in `source-3.98.4.zip`).
- Authors: Ken Händel and the LAME authors; Java packaging by Niklaus Waldispühl.
- License: LGPL-3.0, see `LICENSE` and original source headers. AudioFlow is GPL-3.0-or-later.
- Unmodified release JAR: `app/libs/java-lame-3.98.4.jar`.
- JAR SHA-256: `94a4d54a0e28069c6f6a970cee4c5fd544e764c41610b1832e7691cc53c5930e`.
- Source ZIP SHA-256: `f126e66ad228bb2fe566177992b72876edb4b9a1566e38190e4ab7cf0c428697`.

AudioFlow calls the encoder core directly, without the desktop Java Sound wrapper, JNI or FFmpeg. The upstream release is old; Android compatibility is verified by actual encode/decode instrumentation tests, rather than assumed from its age. It is pinned and ships with its complete source so builds and LGPL replacement/modification are possible without a disappearing Maven/JitPack artifact. Rebuild the upstream JAR with its included Gradle build and replace the file above before rebuilding AudioFlow. No upstream code was modified.

MP3 is a lossy encoding. Transcoding a YouTube stream does not increase its original quality. The encoder's open-source license does not grant rights to a recording. The end of the core MP3 patent program does not establish that every implementation is unconditionally patent-free: https://www.audioblog.iis.fraunhofer.com/mp3-software-patents-licenses
