# Production foundation dependencies

Direct coordinates are pinned in backend/build.gradle.kts: `io.ktor:ktor-server-netty-jvm:3.5.2` (maintained Kotlin HTTP server, Apache-2.0), `org.postgresql:postgresql:42.7.11` (JDBC, BSD-2-Clause), and runtime `org.slf4j:slf4j-nop:2.0.18` (no request logging, MIT). No ORM or migration framework was added: immutable numbered SQL migrations are version/checksum validated transactionally. Phase 1E.1 adds V002 for the ACK receipt flag without editing V001.

New transitive families selected by these pinned modules:

| Family | Version | Published license |
|---|---|---|
| Ktor modules | 3.5.2 | Apache-2.0 |
| Netty modules | 4.2.16.Final | Apache-2.0; retain bundled third-party notices |
| Typesafe config | 1.4.9 | Apache-2.0 |
| Kotlin stdlib/reflect | 2.3.21 | Apache-2.0 |
| kotlinx coroutines | 1.11.0 | Apache-2.0 |
| kotlinx serialization | 1.11.0 | Apache-2.0 |
| kotlinx IO | 0.9.1 | Apache-2.0 |
| Jetty ALPN API / parent | 1.1.3.v20160715 / 21 | Apache-2.0 or EPL-1.0 |
| SLF4J API/NOP/BOM | 2.0.18 | MIT |
| Bouncy Castle BOM (metadata only) | 1.84 | Bouncy Castle license (MIT-style) |

Existing checker-qual and annotations artifacts retain their published licenses and existing checksums. Distribution JARs retain embedded license/NOTICE files. Ktor's transitive versions apply to backend/JVM tests; Android's existing production dependency graph and libsignal 0.102.1/SQLCipher 4.19.0 pins were not upgraded. No new homegrown message cryptography is introduced. Review resolved dependency reports and transitive licenses with each update.

All 223 newly recorded artifacts (including POM/module metadata, binaries, sources and Javadocs) were compared by SHA-256 with fresh official Maven Central/Google downloads on 2026-09-09 before the strict build. Kotlin multiplatform `-metadata-VERSION.jar` logical names map to the published `-VERSION.jar` URL as declared by module metadata. Existing verification entries were preserved. Checksum pinning detects changes after review; it does not prove publisher integrity or absence of vulnerabilities.

The strict IDE task now validates 533 source/Javadoc attachments as well as plugin and application/test classpaths and the Gradle source ZIP. Do not disable verification or trust an entire repository when Android Studio requests another attachment.

Primary references: [Ktor releases](https://ktor.io/docs/releases.html), [PostgreSQL JDBC](https://jdbc.postgresql.org/), [Netty](https://netty.io/), and the reviewed versioned POM/module files on [Maven Central](https://repo.maven.apache.org/maven2/).
