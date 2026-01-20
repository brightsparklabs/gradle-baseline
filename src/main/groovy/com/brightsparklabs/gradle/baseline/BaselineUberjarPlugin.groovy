/*
 * Maintained by brightSPARK Labs.
 * www.brightsparklabs.com
 *
 * Refer to LICENSE at repository root for license details.
 */

package com.brightsparklabs.gradle.baseline

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.bundling.Zip

/**
 * The brightSPARK Labs Baseline Uberjar Plugin.
 *
 * This simplifies adding a task to create an uberjar (a.k.a. fat jar).
 */
public class BaselineUberjarPlugin implements Plugin<Project> {

    // -------------------------------------------------------------------------
    // IMPLEMENTATION: Plugin<Project>
    // -------------------------------------------------------------------------

    public void apply(Project project) {
        setupShadowJar(project)
    }

    // --------------------------------------------------------------------------
    // PRIVATE METHODS
    // -------------------------------------------------------------------------

    private static void setupShadowJar(final Project project) {
        project.plugins.apply "java"
        project.plugins.apply "com.github.johnrengelman.shadow"

        // Set zip64 to true so that our zip files are able to contain more than 65535 files
        // and support files greater than 4GB in size.
        project.tasks.named("shadowJar") {
            it.setProperty("zip64", true)
        }
        project.tasks.withType(Zip).configureEach {
            it.setZip64(true)
        }

        addTaskAlias(project, project.shadowJar)
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
