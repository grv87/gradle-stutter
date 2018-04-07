package org.ajoberstar.gradle.stutter;

import java.util.Set;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;
import java.util.stream.Collectors;
import java.net.URL;
import java.net.MalformedURLException;
import java.io.IOException;
import java.io.UncheckedIOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.HttpURLConnection;
import org.gradle.util.GradleVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.testing.Test;
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension;

public class StutterPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    StutterExtension stutter = project.getExtensions().create("stutter", StutterExtension.class, project);

    Directory locksDir = project.getLayout().getProjectDirectory().dir(".stutter");
    stutter.getLockDir().set(locksDir);

    project.getPluginManager().withPlugin("java", plugin -> {
      createWriteLocksTask(project, stutter);
      createCompatTestTasks(project, stutter);
    });
  }

  private void createWriteLocksTask(Project project, StutterExtension stutter) {
    Task lock = project.getTasks().create("stutterWriteLocks");
    lock.setGroup("plugin development");
    lock.setDescription("Generate lock files of Gradle versions to test for compatibility.");
    lock.doLast(task -> {
      try {
        URL serviceUrl = new URL("http://services.gradle.org/versions/all");
        JsonNode versions = new ObjectMapper().readValue(serviceUrl, JsonNode.class);

        Set<GradleVersion> allVersions = StreamSupport.stream(versions.spliterator(), false)
            // don't include broken versions
            .filter(node -> !node.get("broken").asBoolean())
            // include final versions and at user option the active rc and/or nightly
            .filter(node -> {
              boolean finalVersion = node.get("rcFor").asText().isEmpty()
                  && node.get("milestoneFor").asText().isEmpty()
                  && !node.get("nightly").asBoolean();
              boolean activeRc = stutter.isIncludeActiveRc() && node.get("activeRc").asBoolean();
              boolean activeNightly = stutter.isIncludeActiveNightly() && node.get("nightly").asBoolean();
              return finalVersion || activeRc || activeNightly;
            })
            .map(node -> node.get("version").asText())
            .map(GradleVersion::version)
            .peek(version -> project.getLogger().debug("Stutter found: " + version))
            .collect(Collectors.toSet());

        stutter.writeLockFiles(allVersions);
      } catch (MalformedURLException e) {
        throw new IllegalArgumentException("Services url is invalid.", e);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  private void createCompatTestTasks(Project project, StutterExtension stutter) {
    JavaPluginConvention java = project.getConvention().getPlugin(JavaPluginConvention.class);
    SourceSet sourceSet = java.getSourceSets().create("compatTest");

    Task root = project.getTasks().create("compatTest");
    root.setGroup("verification");
    root.setDescription("Run compatibility tests against all supported Gradle versions.");
    project.getTasks().getByName("check").dependsOn(root);

    AtomicBoolean anyVersions = new AtomicBoolean(false);
    stutter.getLockedVersions().forEach(gradleVersion -> {
      anyVersions.set(true);
      Test task = project.getTasks().create("compatTest" + gradleVersion.getVersion(), Test.class);
      task.setGroup("verification");
      task.setDescription("Run compatibility tests against Gradle " + gradleVersion.getVersion());
      task.setTestClassesDirs(sourceSet.getOutput().getClassesDirs());
      task.setClasspath(sourceSet.getRuntimeClasspath());
      task.systemProperty("compat.gradle.version", gradleVersion.getVersion());
      root.dependsOn(task);
    });

    if (!anyVersions.get()) {
      root.doFirst(task -> {
        throw new IllegalStateException("No versions found to test. Configure the stutter extension and run stutterWriteLocks.");
      });
    }

    project.getPluginManager().withPlugin("java-gradle-plugin", plugin2 -> {
      GradlePluginDevelopmentExtension gradlePlugin = project.getExtensions().getByType(GradlePluginDevelopmentExtension.class);
      gradlePlugin.testSourceSets(sourceSet);
    });
  }
}
