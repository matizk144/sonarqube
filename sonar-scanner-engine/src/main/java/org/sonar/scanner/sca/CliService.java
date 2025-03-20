/*
 * SonarQube
 * Copyright (C) 2009-2025 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.scanner.sca;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.sonar.api.batch.fs.internal.DefaultInputModule;
import org.sonar.api.platform.Server;
import org.sonar.api.utils.System2;
import org.sonar.core.util.ProcessWrapperFactory;
import org.sonar.scanner.config.DefaultConfiguration;
import org.sonar.scanner.repository.TelemetryCache;
import org.sonar.scanner.scm.ScmConfiguration;
import org.sonar.scm.git.JGitUtils;

/**
 * The CliService class is meant to serve as the main entrypoint for any commands
 * that should be executed by the CLI. It will handle manages the external process,
 * raising any errors that happen while running a command, and passing back the
 * data generated by the command to the caller.
 */
public class CliService {
  private static final Logger LOG = LoggerFactory.getLogger(CliService.class);
  public static final String EXCLUDED_MANIFESTS_PROP_KEY = "sonar.sca.excludedManifests";

  private final ProcessWrapperFactory processWrapperFactory;
  private final TelemetryCache telemetryCache;
  private final System2 system2;
  private final Server server;
  private final ScmConfiguration scmConfiguration;

  public CliService(ProcessWrapperFactory processWrapperFactory, TelemetryCache telemetryCache, System2 system2, Server server, ScmConfiguration scmConfiguration) {
    this.processWrapperFactory = processWrapperFactory;
    this.telemetryCache = telemetryCache;
    this.system2 = system2;
    this.server = server;
    this.scmConfiguration = scmConfiguration;
  }

  public File generateManifestsZip(DefaultInputModule module, File cliExecutable, DefaultConfiguration configuration) throws IOException, IllegalStateException {
    long startTime = system2.now();
    boolean success = false;
    try {
      String zipName = "dependency-files.zip";
      Path zipPath = module.getWorkDir().resolve(zipName);
      List<String> args = new ArrayList<>();
      args.add(cliExecutable.getAbsolutePath());
      args.add("projects");
      args.add("save-lockfiles");
      args.add("--zip");
      args.add("--zip-filename");
      args.add(zipPath.toAbsolutePath().toString());
      args.add("--directory");
      args.add(module.getBaseDir().toString());

      String excludeFlag = getExcludeFlag(module, configuration);
      if (excludeFlag != null) {
        args.add("--exclude");
        args.add(excludeFlag);
      }

      boolean scaDebug = configuration.getBoolean("sonar.sca.debug").orElse(false);
      if (LOG.isDebugEnabled() || scaDebug) {
        LOG.info("Setting CLI to debug mode");
        args.add("--debug");
      }

      Map<String, String> envProperties = new HashMap<>();
      // sending this will tell the CLI to skip checking for the latest available version on startup
      envProperties.put("TIDELIFT_SKIP_UPDATE_CHECK", "1");
      envProperties.put("TIDELIFT_ALLOW_MANIFEST_FAILURES", "1");
      envProperties.put("TIDELIFT_CLI_INSIDE_SCANNER_ENGINE", "1");
      envProperties.put("TIDELIFT_CLI_SQ_SERVER_VERSION", server.getVersion());
      envProperties.putAll(ScaProperties.buildFromScannerProperties(configuration));

      LOG.info("Running command: {}", args);
      LOG.info("Environment properties: {}", envProperties);

      Consumer<String> logConsumer = LOG.atLevel(Level.INFO)::log;
      processWrapperFactory.create(module.getWorkDir(), logConsumer, logConsumer, envProperties, args.toArray(new String[0])).execute();
      LOG.info("Generated manifests zip file: {}", zipName);
      success = true;
      return zipPath.toFile();
    } finally {
      telemetryCache.put("scanner.sca.execution.cli.duration", String.valueOf(system2.now() - startTime));
      telemetryCache.put("scanner.sca.execution.cli.success", String.valueOf(success));
    }
  }

  private @Nullable String getExcludeFlag(DefaultInputModule module, DefaultConfiguration configuration) throws IOException {
    List<String> configExcludedPaths = getConfigExcludedPaths(configuration);
    List<String> scmIgnoredPaths = getScmIgnoredPaths(module);

    ArrayList<String> mergedExclusionPaths = new ArrayList<>();
    mergedExclusionPaths.addAll(configExcludedPaths);
    mergedExclusionPaths.addAll(scmIgnoredPaths);

    String workDirExcludedPath = getWorkDirExcludedPath(module);
    if (workDirExcludedPath != null) {
      mergedExclusionPaths.add(workDirExcludedPath);
    }

    if (mergedExclusionPaths.isEmpty()) {
      return null;
    }

    // wrap each exclusion path in quotes to handle commas in file paths
    return toCsvString(mergedExclusionPaths);
  }

  private static List<String> getConfigExcludedPaths(DefaultConfiguration configuration) {
    String[] excludedPaths = configuration.getStringArray(EXCLUDED_MANIFESTS_PROP_KEY);
    if (excludedPaths == null) {
      return List.of();
    }
    return Arrays.stream(excludedPaths).toList();
  }

  private List<String> getScmIgnoredPaths(DefaultInputModule module) {
    var scmProvider = scmConfiguration.provider();
    // Only Git is supported at this time
    if (scmProvider == null || scmProvider.key() == null || !scmProvider.key().equals("git")) {
      return List.of();
    }

    if (scmConfiguration.isExclusionDisabled()) {
      // The user has opted out of using the SCM exclusion rules
      return List.of();
    }

    Path baseDirPath = module.getBaseDir();
    List<String> scmIgnoredPaths = JGitUtils.getAllIgnoredPaths(baseDirPath);
    if (scmIgnoredPaths.isEmpty()) {
      return List.of();
    }
    return scmIgnoredPaths.stream()
      .map(ignoredPathRel -> {
        boolean isDirectory = Files.isDirectory(baseDirPath.resolve(ignoredPathRel));
        // Directories need to get turned into a glob for the Tidelift CLI
        return isDirectory ? (ignoredPathRel + "/**") : ignoredPathRel;
      })
      .toList();
  }

  private static String getWorkDirExcludedPath(DefaultInputModule module) {
    Path baseDir = module.getBaseDir().toAbsolutePath().normalize();
    Path workDir = module.getWorkDir().toAbsolutePath().normalize();

    if (workDir.startsWith(baseDir)) {
      // workDir is inside baseDir, so return the relative path as a glob
      Path relativeWorkDir = baseDir.relativize(workDir);
      return relativeWorkDir + "/**";
    }

    return null;
  }

  private static String toCsvString(List<String> values) throws IOException {
    StringWriter sw = new StringWriter();
    try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
      printer.printRecord(values);
    }
    // trim to remove the trailing newline
    return sw.toString().trim();
  }
}
