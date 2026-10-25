# Local resource constraints

- Do not start Android emulators unless the user explicitly requests one. Use lightweight unit tests and APK/lint compilation for routine development; report device checks as unverified when not run.
- `/tmp` is RAM-backed on this host. Never put Android SDKs, system images, AVDs, toolchains, screenshots, release bundles or other large artifacts there.
- Persistent toolchain: `/home/lab/.local/share/OBDMaru/toolchain`; temporary build files: `/home/lab/.local/share/OBDMaru/build-tmp`; logs/releases/artifacts under `/home/lab/.local/share/OBDMaru/`.
- Build with one Gradle worker, no retained daemon, Kotlin compilation in-process and a Gradle heap cap of 768 MiB. Set TMPDIR and java.io.tmpdir to the persistent build-tmp path.
