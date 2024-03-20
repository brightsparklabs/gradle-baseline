# gradle-baseline

[![Build Status](https://github.com/brightsparklabs/gradle-baseline/actions/workflows/gradle-plugins.yml/badge.svg)](https://github.com/brightsparklabs/gradle-baseline/actions/workflows/gradle-plugins.yml)
[![Gradle Plugin](https://img.shields.io/gradle-plugin-portal/v/com.brightsparklabs.gradle.baseline)](https://plugins.gradle.org/plugin/com.brightsparklabs.gradle.baseline)

Applies brightSPARK Labs standardisation to gradle projects.

## Compatibility

| Plugin Version | Gradle Version | Java Version
| -------------- | -------------- | ------------
| 4.x.y          | 8.x.y          | 17
| 3.x.y          | 7.x.y          | 17
| 2.x.y          | 7.x.y          | 11
| 1.x.y          | 6.x.y          | 11

## Build

```shell
./gradlew build
```

## Publishing

To publish a new version:

* Update `gradle/libs.versions.toml` ensuring `versionErrorproneCore` is set appropriately as per
  the notes in there.
* Ensure `ERRORPRONE_CORE_VERSION` in `BaselinePlugin.groovy` references the same version.
* Use `git flow` to merge it into `master`.
* Push `master` and the CI server will publish via `./gradlew publishPlugins`.

## Usage

```groovy
// file: build.gradle

plugins {
    id 'com.brightsparklabs.gradle.baseline' version '<version>'
}
```

## Configuration

Use the following configuration block to configure the plugin:

```groovy
// file: build.gradle

bslBaseline {
    /** [Optional] The license header to prefix each file with. Defaults to the below. */
    licenseHeader = """/*
                      | * Maintained by brightSPARK Labs.
                      | * www.brightsparklabs.com
                      | *
                      | * Refer to LICENSE at repository root for license details.
                      | */
                    """.stripMargin("|")

    // ------------------------------------------------------------
    // [Optional] S3 bucket file upload configuration.
    // ------------------------------------------------------------

    /** The name of the S3 bucket to upload files to. */
    deploy.s3.bucketName = "bsl.customer.project.environment.aws.s3.service"

    /**
     * [Optional] The region of the S3 bucket. If unset, the AWS SDK will attempt to pull the
     * region from the system. Default: unset.
     */
    deploy.s3.region = "ap-southeast-2"

    /** [Optional] The prefix to prepend to uploaded files. Default: None. */
    deploy.s3.prefix = "${project.name}-${project.version}-"

    /**
     * The absolute filepaths of the files to upload to the S3 bucket. Each filepath is treated
     * as a regex, and matched against all files in the project's `build` directory. All matched
     * files are uploaded.
     */
    deploy.s3.filesToUpload = [
            // Upload individual files.
            "${layout.buildDirectory.dir('dist').get()}/release.tgz",
            "${layout.buildDirectory.dir('dist').get()}/release.tgz.sha256",
            // Upload all files in a directory, including files within subdirectories.
            "${layout.buildDirectory.dir('dist').get()}/.*",
            // Upload all files in a directory, excluding files within subdirectories.
            "${layout.buildDirectory.dir('dist').get()}/[^/]*",
    ]

    // NOTE: The following options are useful for development. For example, if you want to test 
    // uploading files to a local MinIO instance.

    /**
     * [Optional] The endpoint to upload files to. This value overrides the default AWS
     * endpoint, and allows files to be uploaded to any S3-compatible storage. For example, files
     * could be uploaded to a local MinIO instance by setting this value to
     * "http://localhost:9000". Default: unset.
     */
    deploy.s3.endpointOverride = "http://localhost:9000"

    /**
     * [Optional] The name of the profile used to access the S3 bucket. The profile must exist
     * within the `~/.aws/credentials` file. If unset, the AWS SDK will use the "default" profile
     * set within the system. Default: unset.
     */
    deploy.s3.profile = "dev"
}
```

## Upgrade notes

### Upgrading dependencies

To upgrade the dependencies of this project, in the base directory (which contains the
`build.gradle` file) run the following command:

```bash
./gradlew useLatestVersionsCheck
```

This will list all the gradle dependencies that can be upgraded, and after checking these,
dependency versions can be updated manually within the `gradle/libs.versions.toml` file.

**IMPORTANT**

* When bumping the `errorprone` plugin, it is important to keep the `error_prone_core` dependency
  aligned. Please refer to `gradle/libs.versions.toml` for details.
* When bumping `spock` you must stay aligned with the version of Groovy that is used by the current
   gradle version. E.g. Gradle 8.1.1 uses Groovy 3.0, so you cannot use
  `org.spockframework:spock-bom:2.3-groovy-4.0` since that specifies Groovy 4.0.

### Upgrading gradle

In order to update the gradle version, you should refer to the relevant documentation provided by
gradle ([Example](https://docs.gradle.org/current/userguide/upgrading_version_8.html)).

```bash
# See deprecation warnings in the console.
gradle help --warning-mode=all
```
After addressing these warnings you can upgrade to the next version of gradle.

```bash
# Set gradle wrapper version.
gradle wrapper --gradle-version <VERSION>
```

This plugin should be tested on a local project before pushing, which can be done with the steps
in the *"Testing during development"* section.

## Testing during development

To test plugin changes during development:

```bash
# bash

# create a test application
mkdir gradle-baseline-test
cd gradle-baseline-test
gradle init --type java-application --dsl groovy
# add the plugin (NOTE: do not specify a version)
sed -i "/plugins/ a id 'com.brightsparklabs.gradle.baseline'" build.gradle

# setup git (plugin requires repo to be under git control)
git init
git add .
git commit "Initial commit"
git tag -a -m "Tag v0.0.0" 0.0.0

# run using the development version of the plugin
gradlew --include-build /path/to/gradle-baseline <task>
```

## Features

- Standardises the following:
    - Code formatting rules.
    - Static code analyser configuration.
    - Uber JAR packaging.
- Checks for dependency updates/vulnerabilities.
- Checks for allowed license on dependencies.
- Applies  a `VERSION` file to the root of the JAR containing the project version.
- Adds a task to upload files to an S3 bucket.

## Allowed licenses

By default, only the following licenses for dependencies are allowed:

- MIT License
- Apache 2.0 License
- Public Domain License

This default list can be modified per-project by running the `bslOverrideAllowedLicenses` task to
expose the config file located at `/brightsparklabs/baseline/allowed-licenses.json`.

The Documentation for this JSON Format can be found within the [Licence Report
Docs](https://github.com/jk1/Gradle-License-Report#allowed-licenses-file).

## Bundled plugins

The following plugins are currently bundled in automatically:

- [Spotless](https://plugins.gradle.org/plugin/com.diffplug.gradle.spotless)
  for formatting.
    - `spotlessCheck` to check code.
    - `spotlessApply` to update code.
    - Formatting can be disabled for blocks of code if needed via:

            // spotless:off
            final var doNotFormat =
              this
                .specific()
                    .codeBlock()
            // spotless:on

- [Error Prone](https://plugins.gradle.org/plugin/net.ltgt.errorprone) for
  static code analysis.
- [Gradle
  Versions](https://plugins.gradle.org/plugin/com.github.ben-manes.versions)
  for stale dependency checks.
    - `dependencyUpdates` to check for updated dependencies.
- [Use Latest
  Versions](https://plugins.gradle.org/plugin/se.patrikerdes.use-latest-versions)
  plugins for dependency updates.
    - `useLatestVersions` to update dependencies in `build.gradle`.
    - `useLatestVersionsCheck` to check if the updates were applied correctly.
- [OWASP](https://plugins.gradle.org/plugin/org.owasp.dependencycheck) plugin
  for vulnerability dependency checks.
    - `dependencyCheckAnalyze` to check for vulnerabilities.
- [Shadow](https://plugins.gradle.org/plugin/com.github.johnrengelman.shadow) plugin
  enables the creation of fat jars.
    - `shadowJar` to generate fat jars.
- [License Report](https://plugins.gradle.org/plugin/com.github.jk1.dependency-license-report) for
  generating reports about the licenses of dependencies
    - `generateLicenseReport` to generate a license report.
    - `checkLicense` to verify the licenses of the dependencies are allowed.

## Licenses

Refer to the `LICENSE` file for details.
