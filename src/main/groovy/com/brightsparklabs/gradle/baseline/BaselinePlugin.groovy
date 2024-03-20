/*
 * Maintained by brightSPARK Labs.
 * www.brightsparklabs.com
 *
 * Refer to LICENSE at repository root for license details.
 */

package com.brightsparklabs.gradle.baseline

import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.google.common.base.Strings
import groovy.io.FileType
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3ClientBuilder
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception

import java.nio.file.Path
import java.nio.file.Paths

/**
 * The brightSPARK Labs Baseline Plugin.
 */
public class BaselinePlugin implements Plugin<Project> {
    // -------------------------------------------------------------------------
    // CONSTANTS
    // -------------------------------------------------------------------------

    /** Project name of the test-case which specifically needs to skip loading ErrorProne */
    public static final String TEST_PROJECT_NAME = "BaselinePluginTest-ProjectName"

    /** Version of error_prone_core to add to all projects. For details of why this needs to be
     * added, refer to the errorprone plugin's `README`. */
    private static final String ERRORPRONE_CORE_VERSION = '2.20.0'

    // -------------------------------------------------------------------------
    // INSTANCE VARIABLES
    // -------------------------------------------------------------------------

    /** Directory this plugin stores it generated files to. */
    File baselineBuildDir

    /** Directory this plugin looks for override files in. */
    File baselineOverrideDir

    // -------------------------------------------------------------------------
    // IMPLEMENTATION: Plugin<Project>
    // -------------------------------------------------------------------------

    public void apply(Project project) {
        this.baselineBuildDir = new File("${project.buildDir}/brightsparklabs/baseline/")
        this.baselineBuildDir.mkdirs()
        this.baselineOverrideDir = new File("${project.projectDir}/brightsparklabs/baseline/")
        // NOTE: Do not create `this.baselineOverrideDir` here as it is noise if empty.
        //       Create only when files need to be written to it.

        // set general properties
        project.group = "com.brightsparklabs"

        def versionProcess = "git describe --always --dirty".execute()
        versionProcess.waitFor()
        project.version = versionProcess.exitValue() == 0 ? versionProcess.text.trim() : "0.0.0-UNKNOWN"

        def config = project.extensions.create("bslBaseline", BaselinePluginExtension)

        // Enforce standards.
        includeVersionInJar(project)
        setupCodeFormatter(project, config)
        setupStaleDependencyChecks(project)
        setupTestCoverage(project)
        setupVulnerabilityDependencyChecks(project)
        setupShadowJar(project)
        setupDependencyLicenseReport(project)
        setupDeployment(project, config)

        /*
         * ErrorProne cannot be loaded dynamically in our test case due to a class-loading exception
         *
         * The exception with the missing class is:
         *
         *   java.lang.NoClassDefFoundError: org/gradle/kotlin/dsl/ConfigurationExtensionsKt
         *
         * This needs to be loaded via the `afterEvaluate` phase of Gradle, as it needs to be
         * loaded via `dependences.errorprone` which is only available after loading the plugin.
         * With the way our test-cases run, we try to load the plugins dynamically which is
         * incompatible with loading the dependency via `afterEvaluate`.
         *
         * Therefore we disable this plugin from being loaded *specifically* in the test case.
         */
        if (!project.getName().equals(TEST_PROJECT_NAME)) {
            setupCodeQuality(project)
        }
    }

    // --------------------------------------------------------------------------
    // PRIVATE METHODS
    // -------------------------------------------------------------------------

    private void includeVersionInJar(Project project) {
        def versionFile = project.file("${this.baselineBuildDir}/VERSION")
        versionFile.text = project.version

        project.afterEvaluate {
            if (project.tasks.findByName('processResources')) {
                project.processResources {
                    from(versionFile)
                    // Required by Gradle 7.
                    duplicatesStrategy 'include'
                }
            }
        }
    }

    private void setupCodeFormatter(Project project, BaselinePluginExtension config) {
        project.plugins.apply "com.diffplug.spotless"
        addTaskAlias(project, project.spotlessApply)
        addTaskAlias(project, project.spotlessCheck)

        project.afterEvaluate {
            // NOTE: config is only available after project is evaluated, so retrieve in this block.
            def header = config.licenseHeader

            project.spotless {
                // Always format Gradle files.
                groovyGradle {
                    greclipse()
                    indentWithSpaces(4)

                    // Allow formatting to be disabled via: `spotless:off` / `spotless:on` comments.
                    toggleOffOn()
                }

                if (isJavaProject(project)) {
                    java {
                        licenseHeader(header)
                        googleJavaFormat().aosp()

                        // Allow formatting to be disabled via: `spotless:off` / `spotless:on` comments.
                        toggleOffOn()
                    }
                }

                if (isGroovyProject(project)) {
                    groovy {
                        licenseHeader(header)
                        // excludes all Java sources within the Groovy source dirs
                        excludeJava()

                        greclipse()
                        indentWithSpaces(4)

                        // Allow formatting to be disabled via: `spotless:off` / `spotless:on` comments.
                        toggleOffOn()
                    }
                }
            }
        }
    }

    private void setupCodeQuality(Project project) {
        project.plugins.apply "net.ltgt.errorprone"

        project.afterEvaluate {
            if (!isJavaProject(project) && !isGroovyProject(project)) {
                // Nothing to configure plugin for.
                return
            }

            // Ensure a repository is defined so the errorprone dependency below  can be obtained.
            project.repositories { mavenCentral() }

            // This needs to be added to all projects which want to use errorprone. For details
            // refer to the errorprone plugin's `README`.
            project.dependencies {
                errorprone("com.google.errorprone:error_prone_core:${ERRORPRONE_CORE_VERSION}")
            }

            // Set globally-applied errorprone options here
            // Options are listed here: https://github.com/tbroyer/gradle-errorprone-plugin
            // Example disabling 'MissingSummary' warnings:
            /*
             project.tasks.named("compileTestJava").configure {
             options.errorprone.disable("MissingSummary")
             }
             project.tasks.named("compileJava").configure {
             options.errorprone.disable("MissingSummary")
             }
             */
            project.tasks.named("compileJava").configure {
                // Warnings in generated code are irrelevant, ignore them.
                options.errorprone.disableWarningsInGeneratedCode = true
            }
        }
    }

    private static void setupTestCoverage(final Project project) {
        project.plugins.apply "jacoco"

        project.afterEvaluate {
            if (!isJavaProject(project) && !isGroovyProject(project)) {
                // Nothing to configure plugin for.
                return
            }

            // Task will only exist if its a Java project.
            if (project.tasks.findByName('jacocoTestReport')) {
                project.jacocoTestReport.dependsOn 'test'
                addTaskAlias(project, project.jacocoTestReport)
            }
        }
    }

    private void setupStaleDependencyChecks(final Project project) {
        project.plugins.apply "com.github.ben-manes.versions"

        def isUnstable = { String version ->
            // Versions are deemed unstable if the version string contains a pre-release flag.
            return version ==~ /(?i).*-(alpha|beta|rc|cr|m|pre|).*/
        }

        // Only use new dependencies versions if they are a stable release.
        project.tasks.named("dependencyUpdates").configure {
            rejectVersionIf {
                isUnstable(it.candidate.version)
            }
        }

        addTaskAlias(project, project.dependencyUpdates)

        project.plugins.apply "se.patrikerdes.use-latest-versions"
        addTaskAlias(project, project.useLatestVersions)
        addTaskAlias(project, project.useLatestVersionsCheck)
    }

    private static void setupVulnerabilityDependencyChecks(final Project project) {
        project.plugins.apply "org.owasp.dependencycheck"
        addTaskAlias(project, project.dependencyCheckAnalyze)
    }

    private static void setupShadowJar(final Project project) {
        project.plugins.apply "java"
        project.plugins.apply "com.github.johnrengelman.shadow"
        addTaskAlias(project, project.shadowJar)
    }

    private void setupDependencyLicenseReport(final Project project) {
        project.plugins.apply "com.github.jk1.dependency-license-report"
        addTaskAlias(project, project.generateLicenseReport)
        addTaskAlias(project, project.checkLicense)

        project.afterEvaluate {
            project.licenseReport {
                filters = [
                    new LicenseBundleNormalizer(createDefaultTransformationRules: true)
                ]
                allowedLicensesFile = new File("${baselineBuildDir}/allowed-licenses.json")
            }

            project.checkLicense.dependsOn project.bslGenerateAllowedLicenses
        }

        // ---------------------------------------------------------------------
        // Add custom tasks for working with the plugin's configuration file.
        // ---------------------------------------------------------------------

        /*
         * The checkLicense task requires a java.io.File type as an input, this task manages the
         * creation of this input file as a temp file in the build directory. If an override file
         * exists (see bslOverrideAllowedLicenses task) it will use that, otherwise it will use the
         * default configuration from `allowed-licenses.json` in the resources.
         */
        project.task("bslGenerateAllowedLicenses") {
            group = "brightSPARK Labs - Baseline"
            description = "Generates the configuration file for the `checkLicense` task using " +
                    "either an override file (if it exists) or the default baseline " +
                    "configuration file."

            inputs.files("${this.baselineOverrideDir}/allowed-licenses.json").optional()
            outputs.file("${this.baselineBuildDir}/allowed-licenses.json")

            doLast {
                String allowedLicensesConfig = inputs.files.singleFile.exists()
                        // Use the exsiting override file (JsonSlurper used to check it is valid JSON).
                        ? JsonOutput.toJson(new JsonSlurper().parseText(inputs.files.singleFile.text))
                        // No override file, use the default.
                        : getClass().getResourceAsStream("/allowed-licenses.json").getText();

                outputs.files.singleFile.text = allowedLicensesConfig
            }
        }

        // Add a task for easily creating an override file.
        project.task("bslOverrideAllowedLicenses") {
            group = "brightSPARK Labs - Baseline"
            description = "Creates an override file for the types of allowed licenses that " +
                    "dependencies can have. This config file is used by the `checkLicense` task."

            outputs.file("${this.baselineOverrideDir}/allowed-licenses.json")

            doLast {
                // Only create the directory if there is something to put in it.
                this.baselineOverrideDir.mkdirs()

                // Seed the file with the default configuration.
                def outputFile = outputs.files.singleFile
                outputFile.text = getClass().getResourceAsStream("/allowed-licenses.json").getText()

                logger.lifecycle("Override file created at: [${outputFile}]")
            }
        }
    }

    /**
     * Setup tasks that deploy releases.
     *
     * @param project The Gradle Project object.
     * @param config The Baseline Plugin configuration object.
     */
    private static void setupDeployment(final Project project, final BaselinePluginExtension config) {
        project.afterEvaluate {
            // NOTE: config is only available after project is evaluated, so retrieve in this block.
            setupDeployToS3(project, config.deploy.s3)
        }
    }

    /**
     * Setup the `bslDeployToS3` task that deploys release files to an S3 bucket.
     *
     * @param project The Gradle Project object.
     * @param s3DeployConfig The S3 deployment configuration object.
     */
    private static void setupDeployToS3(
            final Project project, final S3DeployConfig s3DeployConfig
    ) {
        final String bucketName = s3DeployConfig.bucketName
        final String region = s3DeployConfig.region
        final String prefix = s3DeployConfig.prefix
        final Set<String> filesToUpload = s3DeployConfig.filesToUpload
        final String endpointOverride = s3DeployConfig.endpointOverride
        final String profile = s3DeployConfig.profile

        final def bucketNameIsEmpty = Strings.isNullOrEmpty(bucketName)
        final def filesToUploadIsEmpty = filesToUpload == null || filesToUpload.isEmpty()
        if (bucketNameIsEmpty && filesToUploadIsEmpty) {
            // Both those keys are not sent, indicating that the user does not plan to upload
            // anything to S3. No point adding the task, so return early.
            return
        }

        // Error early if configuration in invalid.
        if (bucketNameIsEmpty) {
            def error = "`bslGradle.deploy.s3.bucketName` cannot be null or empty. Value was:" +
                    " `${bucketName}`"
            project.logger.error(error)
            throw new IllegalStateException(error)
        }
        if (filesToUploadIsEmpty) {
            def error = "`bslGradle.deploy.s3.filesToUpload` cannot be null or empty. Value was:" +
                    " ${filesToUpload}"
            project.logger.error(error)
            throw new IllegalStateException(error)
        }

        // Get the absolute paths of the files to upload. We search for files in the `build`
        // directory, and pattern match against their absolute paths.
        final Set<String> filesToUploadPaths = []
        for (String fileRegex in filesToUpload) {
            project.buildDir.eachFileRecurse(FileType.FILES) { File file ->
                if (file.absolutePath ==~ fileRegex) {
                    filesToUploadPaths.add(file.absolutePath)
                }
            }
        }
        for (String filePathString in filesToUploadPaths) {
            project.logger.lifecycle("Found file to upload: `${filePathString}`")
        }


        project.task("bslDeployToS3") {
            group = "brightSPARK Labs - Baseline"
            description = "Upload files to an S3 bucket. Configure via the `bslBaseline`" +
                    " configuration block."

            doLast {
                final S3ClientBuilder s3Builder = S3Client.builder()

                // By default, the AWS SDK will attempt to pull the region from the system.
                // If configured, we allow for an optional override.
                if (!Strings.isNullOrEmpty(region)) {
                    s3Builder.region(Region.of(region))
                }

                // By default, the AWS SDK will use the "default" profile from the system.
                // If configured, we allow for an optional override.
                if (!Strings.isNullOrEmpty(profile)) {
                    final ClientOverrideConfiguration.Builder s3OverrideConfigBuilder =
                            ClientOverrideConfiguration.builder()
                    s3OverrideConfigBuilder.defaultProfileName(profile)
                    final s3OverrideConfig = s3OverrideConfigBuilder.build()
                    s3Builder.overrideConfiguration(s3OverrideConfig)
                }

                // By default, the AWS SDK will use the default S3 endpoint for the given region.
                // If configured, we allow for an optional override.
                if (!Strings.isNullOrEmpty(endpointOverride)) {
                    URI endpointOverrideURI
                    try {
                        endpointOverrideURI = new URI(endpointOverride)
                    } catch (URISyntaxException e) {
                        logger.error(
                                "A URISyntaxException occurred while parsing the" +
                                " `deploy.s3.endpointOverride` configuration option. Ensure that" +
                                " the given value is a valid URI."
                                )
                        throw e
                    }
                    s3Builder.endpointOverride(endpointOverrideURI)
                }

                S3Client s3Temp
                try {
                    s3Temp = s3Builder.build()
                } catch (SdkClientException e) {
                    logger.error(
                            "An SdkClientException occurred. This may have been caused by an" +
                            " incorrect `deploy.s3.profile` configuration. If the configuration" +
                            " is correct, ensure that the profile exists in the system, and" +
                            " \"Default region name\" is set to a non-empty string."
                            )
                    throw e
                }
                final S3Client s3 = s3Temp

                try {
                    filesToUploadPaths.each { filePathString ->
                        final Path filePath = Paths.get(filePathString)
                        final String fileName = getPrefixedFileName(filePath, prefix)

                        final PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                                .bucket(bucketName)
                                .key(fileName)
                                .build() as PutObjectRequest
                        s3.putObject(putObjectRequest, RequestBody.fromFile(filePath.toFile()))

                        logger.lifecycle(
                                "Successfully uploaded file `${fileName}` into bucket" +
                                " `${bucketName}`."
                                )
                    }
                } catch (S3Exception e) {
                    logger.error(
                            "An S3Exception occurred. This may have been caused by:\n" +
                            "  1. An incorrect `deploy.s3.bucketName` configuration. If the" +
                            " configuration is correct, ensure that the bucket exists.\n" +
                            "\n" +
                            "  2. An incorrect AWS profile configuration. Ensure that the profile" +
                            " used to access the bucket is configured correctly in the system." +
                            " This will either be the profile set using the `deploy.s3.profile`" +
                            " configuration option, or \"default\". The profile can be configured" +
                            " using the following AWS CLI command:\n" +
                            "      `aws configure --profile \${PROFILE_NAME}`\n" +
                            "\n" +
                            "  3. An incorrect `deploy.s3.endpointOverride` configuration. It's" +
                            " possible that hitting the incorrect port of a valid S3 service" +
                            " endpoint will return an S3Exception.\n" +
                            "\n" +
                            "  4. The S3 service specified by `deploy.s3.endpointOverride` has" +
                            " virtual-host-style requests disabled. This will result in a \"The" +
                            " specified bucket is not valid\" error message below. For more" +
                            " information on virtual-host-style requests, see" +
                            " `https://docs.aws.amazon.com/AmazonS3/latest/userguide/VirtualHosting.html#virtual-hosted-style-access`." +
                            " If you're attempting to connect to a local MinIO instance, ensure" +
                            " the `MINIO_DOMAIN` environment variable is set. For more" +
                            " information see" +
                            " `https://min.io/docs/minio/linux/reference/minio-server/settings/core.html#domain`."
                            )
                    throw e
                } catch (SdkClientException e) {
                    logger.error(
                            "An SdkClientException occurred. This may have been caused by an" +
                            " incorrect `deploy.s3.endpointOverride` configuration. If" +
                            " the configuration is correct, ensure that the S3 service at" +
                            " the endpoint is running."
                            )
                    throw e
                }
            }
        }
    }

    /**
     * Return the filename from the given Path with the given prefix prepended. If the filename
     * already has the given prefix, the filename is returned as is.
     *
     * @param filePath The file path.
     * @param prefix The desired filename prefix.
     * @return The name of the file, with the given prefix.
     */
    private static String getPrefixedFileName(final Path filePath, final String prefix) {
        final String fileName = filePath.getFileName().toString()
        if (fileName.startsWith(prefix)) {
            // Prefix already present, do not prepend again.
            return fileName
        }
        return "${prefix}${fileName}"
    }

    /**
     * Returns true if the project compiles Java code. Only reliable if called in `project.afterEvaluate`.
     *
     * @param project The project to check.
     * @return `true` if the `java` plugin has been applied.
     */
    private static boolean isJavaProject(Project project) {
        return project.tasks.findByName('compileJava') != null
    }

    /**
     * Returns true if the project compiles Groovy code. Only reliable if called in `project.afterEvaluate`.
     *
     * @param project The project to check.
     * @return `true` if the `java` plugin has been applied.
     */
    private static boolean isGroovyProject(Project project) {
        return project.tasks.findByName('compileGroovy') != null
    }

    /**
     * Creates a task alias nested under the BSL group for clarity.
     *
     * @param project Gradle Project to add the task to.
     * @param task Task to create an alias of.
     * @param alias Name of the alias.
     */
    private static void addTaskAlias(final Project project, final Task task) {
        def aliasTaskName = 'bsl' + task.name.capitalize()
        def taskDescription = "${task.description.trim()}${task.description.endsWith('.') ? '' : '.'} Alias for `${task.name}`."
        project.task(aliasTaskName) {
            group = "brightSPARK Labs - Baseline"
            description = taskDescription
        }
        project[aliasTaskName].dependsOn task
    }
}
