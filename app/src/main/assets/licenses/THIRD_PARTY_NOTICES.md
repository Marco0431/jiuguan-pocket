# Third-party notices

The original Jiuguan Pocket application, relay plugin, and supporting tools are
licensed under the MIT License in [LICENSE](LICENSE).

## Gradle Wrapper

This repository distributes `gradle/wrapper/gradle-wrapper.jar` from Gradle 8.9.
Gradle is copyright its respective authors and is licensed under Apache License
2.0. The distribution's license and notices are retained in
`licenses/gradle-LICENSE.txt` and `licenses/gradle-NOTICE.txt`.

## AndroidX

The Android application uses AndroidX WebKit 1.12.1 and its resolved AndroidX
dependencies from the Android Open Source Project. AndroidX is licensed under
Apache License 2.0; see `licenses/Apache-2.0.txt`. Dependency-provided notices
remain governed by their original licenses. The APK includes the project license
and these notices in its `assets/licenses` directory.

The resolved AndroidX runtime components are WebKit 1.12.1, Annotation and
Annotation JVM 1.8.1, Annotation Experimental 1.4.1, Core 1.1.0, Lifecycle Runtime
and Common 2.0.0, Arch Core Common 2.0.0, VersionedParcelable 1.1.0, and Collection
1.0.0.

## Kotlin and JetBrains annotations

The runtime dependency graph also includes Kotlin Standard Library and Standard
Library Common 1.7.10, plus JetBrains Annotations 13.0. These projects are
copyright JetBrains s.r.o. and their respective contributors and are licensed
under Apache License 2.0; see `licenses/Apache-2.0.txt`.

## SillyTavern and its dependencies

SillyTavern is a separate project; its source code, user data, configuration, and
installed `node_modules` are not included here. The plugin uses the user's
existing SillyTavern installation and resolves its installed `socks` dependency;
the installer also uses that installation's `yaml` dependency. Those packages and
SillyTavern remain subject to their own licenses. This is an independent
companion project, not an official SillyTavern client or endorsement.

## Test and build dependencies

JUnit, the Android Gradle Plugin, the Android SDK, and other build/test tools are
resolved separately and are not relicensed by this repository's MIT License.
