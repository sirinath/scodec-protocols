scodec-protocols
================

Provides models of common binary protocols using the [scodec library](https://github.com/scodec/scodec).

Administrative
--------------

This project is licensed under a [3-clause BSD license](LICENSE).

The [Typelevel mailing list](https://groups.google.com/forum/#!forum/typelevel) contains release announcements and is generally a good place to go for help. Also consider using the [scodec tag on StackOverflow](http://stackoverflow.com/questions/tagged/scodec).

People are expected to follow the [Typelevel Code of Conduct](http://typelevel.org/conduct.html)
when discussing scodec on the Github page, IRC channel, mailing list,
or other venues.

Concerns or issues can be sent to Michael Pilquist (*mpilquist@gmail.com*) or
to [Typelevel](http://typelevel.org/about.html).

Getting Binaries
----------------

This library works with Scala 2.10 and 2.11.

ScalaDoc for the latest version is available here: http://scodec.github.io/scodec-protocols/latest/api.

### Releases

#### Version 0.1.0

 - Preview release - no source or binary compatibility guarantees.
 - Compatible with Scalaz 7.1.*, Shapeless 2.0.0, and Scalaz-stream 0.5a.


For SBT users:

    libraryDependencies += "org.typelevel" %% "scodec-protocols" % "0.1.0"


For Maven users:

    <dependencies>
      <dependency>
        <groupId>org.typelevel</groupId>
        <artifactId>scodec-protocols_2.11</artifactId>
        <version>0.1.0-SNAPSHOT</version>
      </dependency>
    </dependencies>

### Snapshots

Snapshot builds of the master branch are available on Sonatype's OSS hosting at https://oss.sonatype.org/content/repositories/snapshots/.

For SBT users:

    resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

    libraryDependencies += "org.typelevel" %% "scodec-protocols" % "0.2.0-SNAPSHOT"


For Maven users:

    <repositories>
      <repository>
        <id>sonatype-oss-snapshots</id>
        <name>Sonatype OSS Snapshots</name>
        <url>https://oss.sonatype.org/content/repositories/snapshots/</url>
      </repository>
    </repositories>

    <dependencies>
      <dependency>
        <groupId>org.typelevel</groupId>
        <artifactId>scodec-protocols_2.11</artifactId>
        <version>0.2.0-SNAPSHOT</version>
      </dependency>
    </dependencies>

Building
--------

This project uses sbt. To build, run `sbt publish-local`.
