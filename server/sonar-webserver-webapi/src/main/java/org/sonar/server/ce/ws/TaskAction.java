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
package org.sonar.server.ce.ws;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.api.server.ws.Change;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.db.permission.ProjectPermission;
import org.sonar.core.util.Uuids;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.ce.CeActivityDto;
import org.sonar.db.ce.CeQueueDto;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.permission.GlobalPermission;
import org.sonar.server.user.UserSession;
import org.sonarqube.ws.Ce;

import static org.sonar.server.exceptions.NotFoundException.checkFoundWithOptional;
import static org.sonar.server.user.AbstractUserSession.insufficientPrivilegesException;
import static org.sonar.server.ws.WsUtils.writeProtobuf;

public class TaskAction implements CeWsAction {

  public static final String ACTION = "task";
  public static final String PARAM_TASK_UUID = "id";

  private static final String PARAM_ADDITIONAL_FIELDS = "additionalFields";

  private final DbClient dbClient;
  private final TaskFormatter wsTaskFormatter;
  private final UserSession userSession;

  public TaskAction(DbClient dbClient, TaskFormatter wsTaskFormatter, UserSession userSession) {
    this.dbClient = dbClient;
    this.wsTaskFormatter = wsTaskFormatter;
    this.userSession = userSession;
  }

  @Override
  public void define(WebService.NewController controller) {
    WebService.NewAction action = controller.createAction(ACTION)
      .setDescription("Give Compute Engine task details such as type, status, duration and associated component.<br/>" +
        "Requires one of the following permissions: " +
        "<ul>" +
        "<li>'Administer' at global or project level</li>" +
        "<li>'Execute Analysis' at global or project level</li>" +
        "</ul>" +
        "Since 6.1, field \"logs\" is deprecated and its value is always false.")
      .setResponseExample(getClass().getResource("task-example.json"))
      .setSince("5.2")
      .setChangelog(
        new Change("6.6", "fields \"branch\" and \"branchType\" added"),
        new Change("10.1", "Warnings field will be now always be filled (it is not necessary to mention it explicitly in 'additionalFields'). "
          + "'additionalFields' value `warning' is deprecated."),
        new Change("10.1", "'Project Administrator' is added to the list of allowed permissions to access this endpoint"))
      .setHandler(this);

    action
      .createParam(PARAM_TASK_UUID)
      .setRequired(true)
      .setDescription("Id of task")
      .setExampleValue(Uuids.UUID_EXAMPLE_01);
    action.createParam(PARAM_ADDITIONAL_FIELDS)
      .setSince("6.1")
      .setDescription("Comma-separated list of the optional fields to be returned in response.")
      .setPossibleValues(AdditionalField.possibleValues());
  }

  @Override
  public void handle(Request wsRequest, Response wsResponse) throws Exception {
    String taskUuid = wsRequest.mandatoryParam(PARAM_TASK_UUID);
    try (DbSession dbSession = dbClient.openSession(false)) {
      Ce.TaskResponse.Builder wsTaskResponse = Ce.TaskResponse.newBuilder();
      Optional<CeQueueDto> queueDto = dbClient.ceQueueDao().selectByUuid(dbSession, taskUuid);
      if (queueDto.isPresent()) {
        Optional<ComponentDto> component = loadComponent(dbSession, queueDto.get().getComponentUuid());
        checkPermission(component);
        wsTaskResponse.setTask(wsTaskFormatter.formatQueue(dbSession, queueDto.get()));
      } else {
        CeActivityDto ceActivityDto = checkFoundWithOptional(
          dbClient.ceActivityDao().selectByUuid(dbSession, taskUuid),
          "No activity found for task '%s'", taskUuid);
        Optional<ComponentDto> component = loadComponent(dbSession, ceActivityDto.getComponentUuid());
        checkPermission(component);
        Set<AdditionalField> additionalFields = AdditionalField.getFromRequest(wsRequest);
        maskErrorStacktrace(ceActivityDto, additionalFields);
        wsTaskResponse.setTask(
          wsTaskFormatter.formatActivity(dbSession, ceActivityDto,
            extractScannerContext(dbSession, ceActivityDto, additionalFields)));
      }
      writeProtobuf(wsTaskResponse.build(), wsRequest, wsResponse);
    }
  }

  private Optional<ComponentDto> loadComponent(DbSession dbSession, @Nullable String projectUuid) {
    if (projectUuid == null) {
      return Optional.empty();
    }
    return dbClient.componentDao().selectByUuid(dbSession, projectUuid);
  }

  private void checkPermission(Optional<ComponentDto> component) {
    if (component.isPresent()) {
      checkComponentPermission(component.get());
    } else {
      userSession.checkIsSystemAdministrator();
    }
  }

  private void checkComponentPermission(ComponentDto component) {
    if (userSession.hasPermission(GlobalPermission.ADMINISTER) ||
      userSession.hasPermission(GlobalPermission.SCAN) ||
      userSession.hasComponentPermission(ProjectPermission.ADMIN, component) ||
      userSession.hasComponentPermission(ProjectPermission.SCAN, component)) {
      return;
    }
    throw insufficientPrivilegesException();
  }

  private static void maskErrorStacktrace(CeActivityDto ceActivityDto, Set<AdditionalField> additionalFields) {
    if (!additionalFields.contains(AdditionalField.STACKTRACE)) {
      ceActivityDto.setErrorStacktrace(null);
    }
  }

  @CheckForNull
  private String extractScannerContext(DbSession dbSession, CeActivityDto activityDto, Set<AdditionalField> additionalFields) {
    if (additionalFields.contains(AdditionalField.SCANNER_CONTEXT)) {
      return dbClient.ceScannerContextDao().selectScannerContext(dbSession, activityDto.getUuid())
        .orElse(null);
    }
    return null;
  }

  private enum AdditionalField {
    STACKTRACE("stacktrace"),
    SCANNER_CONTEXT("scannerContext"),
    @Deprecated(forRemoval = true, since = "10.1")
    WARNINGS("warnings");

    private final String label;

    AdditionalField(String label) {
      this.label = label;
    }

    public String getLabel() {
      return label;
    }

    public static Set<AdditionalField> getFromRequest(Request wsRequest) {
      List<String> strings = wsRequest.paramAsStrings(PARAM_ADDITIONAL_FIELDS);
      if (strings == null) {
        return Collections.emptySet();
      }
      return strings.stream()
        .map(s -> {
          for (AdditionalField field : AdditionalField.values()) {
            if (field.label.equalsIgnoreCase(s)) {
              return field;
            }
          }
          return null;
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    }

    public static Collection<String> possibleValues() {
      return Arrays.stream(values())
        .map(AdditionalField::getLabel)
        .toList();
    }
  }
}
