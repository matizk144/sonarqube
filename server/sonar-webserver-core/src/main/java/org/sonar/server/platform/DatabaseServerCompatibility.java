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
package org.sonar.server.platform;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.SonarEdition;
import org.sonar.api.SonarRuntime;
import org.sonar.api.Startable;
import org.sonar.api.utils.MessageException;
import org.sonar.core.documentation.DocumentationLinkGenerator;
import org.sonar.server.platform.db.migration.version.DatabaseVersion;

import static org.sonar.server.log.ServerProcessLogging.STARTUP_LOGGER_NAME;
import static org.sonar.server.platform.db.migration.version.DatabaseVersion.MIN_UPGRADE_VERSION_COMMUNITY_BUILD_READABLE;
import static org.sonar.server.platform.db.migration.version.DatabaseVersion.MIN_UPGRADE_VERSION_HUMAN_READABLE;

public class DatabaseServerCompatibility implements Startable {

  private static final String HIGHLIGHTER = "################################################################################";

  private final DatabaseVersion version;

  private final DocumentationLinkGenerator documentationLinkGenerator;

  private final SonarRuntime sonarRuntime;

  public DatabaseServerCompatibility(DatabaseVersion version, DocumentationLinkGenerator documentationLinkGenerator, SonarRuntime sonarRuntime) {
    this.version = version;
    this.documentationLinkGenerator = documentationLinkGenerator;
    this.sonarRuntime = sonarRuntime;
  }

  @Override
  public void start() {
    DatabaseVersion.Status status = version.getStatus();
    if (status == DatabaseVersion.Status.REQUIRES_DOWNGRADE) {
      throw MessageException.of("Database was upgraded to a more recent version of SonarQube. "
        + "A backup must probably be restored or the DB settings are incorrect.");
    }
    if (status == DatabaseVersion.Status.REQUIRES_UPGRADE) {
      Optional<Long> currentVersion = this.version.getVersion();
      if (currentVersion.isPresent() && currentVersion.get() < DatabaseVersion.MIN_UPGRADE_VERSION) {
        throw MessageException.of(buildVersionTooOldMessage());
      }
      String documentationLink = documentationLinkGenerator.getDocumentationLink("/server-upgrade-and-maintenance/upgrade/upgrade-the-server/roadmap");
      String msg = String.format("The database must be manually upgraded. Please backup the database and browse /setup. "
        + "For more information: %s", documentationLink);
      LoggerFactory.getLogger(DatabaseServerCompatibility.class).warn(msg);
      Logger logger = LoggerFactory.getLogger(STARTUP_LOGGER_NAME);
      logger.warn(HIGHLIGHTER);
      logger.warn(msg);
      logger.warn(HIGHLIGHTER);
    }
  }

  private String buildVersionTooOldMessage() {
    if (sonarRuntime.getEdition() == SonarEdition.COMMUNITY) {
      return "The version of SonarQube you are trying to upgrade from is too old. Please upgrade to the " +
        MIN_UPGRADE_VERSION_COMMUNITY_BUILD_READABLE + " version first.";
    } else {
      return "The version of SonarQube you are trying to upgrade from is too old. Please upgrade to the " +
        MIN_UPGRADE_VERSION_HUMAN_READABLE + " Long-Term Active version first.";
    }

  }

  @Override
  public void stop() {
    // do nothing
  }
}
