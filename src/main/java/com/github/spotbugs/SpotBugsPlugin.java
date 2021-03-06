package com.github.spotbugs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.stream.StreamSupport;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.ConventionMapping;
import org.gradle.api.plugins.quality.CodeQualityExtension;
import org.gradle.api.plugins.quality.internal.AbstractCodeQualityPlugin;
import org.gradle.api.reporting.SingleFileReport;
import org.gradle.api.tasks.SourceSet;
import org.gradle.util.GradleVersion;

/**
 * A plugin for the <a href="https://spotbugs.github.io">SpotBugs</a> byte code analyzer.
 *
 * <p>
 * Declares a <tt>spotbugs</tt> configuration which needs to be configured with the SpotBugs library to be used.
 * Additional plugins can be added to the <tt>spotbugsPlugins</tt> configuration.
 *
 * <p>
 * For projects that have the Java (base) plugin applied, a {@link SpotBugsTask} task is
 * created for each source set.
 *
 * @see SpotBugsTask
 * @see SpotBugsExtension
 */
public class SpotBugsPlugin extends AbstractCodeQualityPlugin<SpotBugsTask> {

    /**
     * Supported Gradle version described at <a href="http://spotbugs.readthedocs.io/en/latest/gradle.html">official
     * manual site</a>.
     */
    private static final GradleVersion SUPPORTED_VERSION = GradleVersion.version("4.0");

    private SpotBugsExtension extension;

    @Override
    protected String getToolName() {
        return "SpotBugs";
    }

    @Override
    protected Class<SpotBugsTask> getTaskType() {
        return SpotBugsTask.class;
    }

    @Override
    protected void beforeApply() {
        verifyGradleVersion(GradleVersion.current());
        configureSpotBugsConfigurations();
        project.afterEvaluate(this::verify);
    }

    private void verify(Project p) {
        p.getTasks().withType(SpotBugsTask.class).forEach(task -> {
            SpotBugsReports reports = task.getReports();
            if (reports.getText() != null && reports.getText().getDestination() == null) {
                String message = String.format(
                        "Task '%s' has no destination for TEXT report. Set reports.text.destination to this task.",
                        task.getName());
                throw new IllegalStateException(message);
            }
            if (reports.getXml() != null && reports.getXml().getDestination() == null) {
                String message = String.format(
                        "Task '%s' has no destination for XML report. Set reports.xml.destination to this task.",
                        task.getName());
                throw new IllegalStateException(message);
            }
            if (reports.getHtml() != null && reports.getHtml().getDestination() == null) {
                String message = String.format(
                        "Task '%s' has no destination for HTML report. Set reports.html.destination. to this task",
                        task.getName());
                throw new IllegalStateException(message);
            }
            if (reports.getEmacs() != null && reports.getEmacs().getDestination() == null) {
                String message = String.format(
                        "Task '%s' has no destination for EMACS report. Set reports.emacs.destination. to this task",
                        task.getName());
                throw new IllegalStateException(message);
            }
        });
    }

    /**
     * Verify that given version is supported by {@link SpotBugsPlugin} or not.
     *
     * @param version
     *            to verify
     * @throws IllegalArgumentException
     *             if given version is not supported
     */
    void verifyGradleVersion(GradleVersion version) throws IllegalArgumentException {
        if (version.compareTo(SUPPORTED_VERSION) < 0) {
            String message = String.format("Gradle version %s is unsupported. Please use %s or later.", version,
                    SUPPORTED_VERSION);
            throw new IllegalArgumentException(message);
        }
    }

    private void configureSpotBugsConfigurations() {
        Configuration configuration = project.getConfigurations().create("spotbugsPlugins");
        configuration.setVisible(false);
        configuration.setTransitive(true);
        configuration.setDescription("The SpotBugs plugins to be used for this project.");
    }

    @Override
    protected CodeQualityExtension createExtension() {
        extension = project.getExtensions().create("spotbugs", SpotBugsExtension.class, project);
        extension.setToolVersion(loadToolVersion());
        return extension;
    }

    String loadToolVersion() {
        URL url = SpotBugsPlugin.class.getClassLoader().getResource("spotbugs-gradle-plugin.properties");
        try (InputStream input = url.openStream()) {
            Properties prop = new Properties();
            prop.load(input);
            return prop.getProperty("spotbugs-version");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected void configureTaskDefaults(SpotBugsTask task, String baseName) {
        task.setPluginClasspath(project.getConfigurations().getAt("spotbugsPlugins"));
        Configuration configuration = project.getConfigurations().getAt("spotbugs");
        configureDefaultDependencies(configuration);
        configureTaskConventionMapping(configuration, task);
        configureReportsConventionMapping(task, baseName);
    }

    protected void configureConfiguration(Configuration configuration) {
        // For an abstract method that was newly defined from v4.8, we need an empty method at here
        // https://github.com/spotbugs/spotbugs-gradle-plugin/issues/22
    }

    private void configureDefaultDependencies(Configuration configuration) {
        configuration.defaultDependencies((DependencySet dependencies) ->
            dependencies.add(project.getDependencies().create("com.github.spotbugs:spotbugs:" + extension.getToolVersion()))
        );
    }

    private void configureTaskConventionMapping(Configuration configuration, SpotBugsTask task) {
        ConventionMapping taskMapping = task.getConventionMapping();
        taskMapping.map("spotbugsClasspath", () -> configuration);
        taskMapping.map("ignoreFailures", extension::isIgnoreFailures);
        taskMapping.map("effort", extension::getEffort);
        taskMapping.map("reportLevel", extension::getReportLevel);
        taskMapping.map("visitors", extension::getVisitors);
        taskMapping.map("omitVisitors", extension::getOmitVisitors);

        taskMapping.map("excludeFilterConfig", extension::getExcludeFilterConfig);
        taskMapping.map("includeFilterConfig", extension::getIncludeFilterConfig);
        taskMapping.map("excludeBugsFilterConfig", extension::getExcludeBugsFilterConfig);

        taskMapping.map("extraArgs", extension::getExtraArgs);
        taskMapping.map("showProgress", extension::isShowProgress);
    }

    private void configureReportsConventionMapping(SpotBugsTask task, final String baseName) {
        task.getReports().all((final SingleFileReport report) -> {
            ConventionMapping reportMapping = conventionMappingOf(report);
            reportMapping.map("enabled", () -> report.getName().equals("xml"));
            reportMapping.map("destination", () -> new File(extension.getReportsDir(), baseName + "." + report.getName()));
        });
    }

    @Override
    protected void configureForSourceSet(final SourceSet sourceSet, SpotBugsTask task) {
        task.setDescription("Run SpotBugs analysis for " + sourceSet.getName() + " classes");
        task.setSource(sourceSet.getAllJava());
        ConventionMapping taskMapping = task.getConventionMapping();
        taskMapping.map("classes", (Callable<FileCollection>) () -> {
            /*
             * As a result of the changes made in gradle 4.0.
             * See https://docs.gradle.org/4.0/release-notes.html - Location of classes in the build directory
             * Compile no longer bundles all classes in one directory build-gradle/classes/main
             * but instead separates classes into build-gradle/classes/{language}/main.
             *
             * We must therefor retrieve all output directories. Filter away the once that don't exist. Add each
             * existing file tree dependency to specified task. And then return the complete fileCollection, contain
             * all .class files available for analysis.
             */
            FileCollection presentClassDirs = sourceSet.getOutput().getClassesDirs().filter(File::exists);
            StreamSupport.stream(presentClassDirs.spliterator(), false)
                    .map(project::fileTree)
                    .forEach(tree -> tree.builtBy(sourceSet.getClassesTaskName()));
            return presentClassDirs;
        });
        taskMapping.map("classpath", sourceSet::getCompileClasspath);
    }
}
