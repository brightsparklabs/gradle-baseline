/*
 * Maintained by brightSPARK Labs.
 * www.brightsparklabs.com
 *
 * Refer to LICENSE at repository root for license details.
 */

package com.brightsparklabs.gradle.baseline

/**
 * Configurable settings for the {@link BaselinePlugin}.
 */
class BaselinePluginExtension {
    /** [Optional] The license header to prefix each file with. */
    String licenseHeader = """/*
                             | * Maintained by brightSPARK Labs.
                             | * www.brightsparklabs.com
                             | *
                             | * Refer to LICENSE at repository root for license details.
                             | */
                           """.stripMargin("|")
    /** [Optional] The release deployment configuration. */
    DeployConfig deploy = new DeployConfig()
}

/**
 * Configurable settings for the deployment.
 */
class DeployConfig {
    /** [Optional] The S3 bucket deployment configuration. */
    S3DeployConfig s3 = new S3DeployConfig()
}

/**
 * Configurable settings for the deploying to an S3 bucket..
 */
class S3DeployConfig {
    /** The name of the S3 bucket to upload files to. */
    String bucketName

    /**
     * [Optional] The region of the S3 bucket. If unset, the AWS SDK will attempt to pull the
     * region from the system. Default: unset.
     */
    String region

    /** [Optional] The prefix to prepend to uploaded files. Default: "". */
    String prefix = ""

    /** The paths of the files to upload to the S3 bucket. */
    Set<String> filesToUpload

    /** [Optional] The endpoint to upload files to. This allows files to be uploaded to any
     * S3-compatible storage. For example, files could be uploaded to a local MinIO instance by
     * setting this value to "http://localhost:9000".*/
    String endpointOverride

    /** [Optional] The name of the profile used to access the S3 bucket. The profile must exist
     * within the `~/.aws/credentials` file. If unset, the AWS SDK will use the "default" profile
     * set within the system. */
    String profile
}