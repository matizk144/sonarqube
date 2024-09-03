/*
 * SonarQube
 * Copyright (C) 2009-2024 SonarSource SA
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
package org.sonar.db.measure;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.sonar.api.utils.System2;
import org.sonar.db.Dao;
import org.sonar.db.DbSession;

import static org.sonar.db.DatabaseUtils.executeLargeInputs;

public class MeasureDao implements Dao {

  private final System2 system2;

  public MeasureDao(System2 system2) {
    this.system2 = system2;
  }

  public int insert(DbSession dbSession, MeasureDto dto) {
    return mapper(dbSession).insert(dto, system2.now());
  }

  /**
   * Update a measure. The measure json value will be overwritten.
   */
  public int update(DbSession dbSession, MeasureDto dto) {
    return mapper(dbSession).update(dto, system2.now());
  }

  /**
   * Unlike {@link #update(DbSession, MeasureDto)}, this method will not overwrite the entire json value,
   * but will update the measures inside the json.
   */
  public int insertOrUpdate(DbSession dbSession, MeasureDto dto) {
    long now = system2.now();
    Optional<MeasureDto> existingMeasureOpt = selectMeasure(dbSession, dto.getComponentUuid());
    if (existingMeasureOpt.isPresent()) {
      MeasureDto existingDto = existingMeasureOpt.get();
      existingDto.getMetricValues().putAll(dto.getMetricValues());
      dto.getMetricValues().putAll(existingDto.getMetricValues());
      dto.computeJsonValueHash();
      return mapper(dbSession).update(dto, now);
    } else {
      dto.computeJsonValueHash();
      return mapper(dbSession).insert(dto, now);
    }
  }

  public Optional<MeasureDto> selectMeasure(DbSession dbSession, String componentUuid) {
    List<MeasureDto> measures = mapper(dbSession).selectByComponentUuids(List.of(componentUuid));
    if (!measures.isEmpty()) {
      // component_uuid column is unique. List can't have more than 1 item.
      return Optional.of(measures.get(0));
    }
    return Optional.empty();
  }

  public Optional<MeasureDto> selectMeasure(DbSession dbSession, String componentUuid, String metricKey) {
    List<MeasureDto> measures = selectByComponentUuidsAndMetricKeys(dbSession, List.of(componentUuid), List.of(metricKey));
    // component_uuid column is unique. List can't have more than 1 item.
    if (measures.size() == 1) {
      return Optional.of(measures.get(0));
    }
    return Optional.empty();
  }

  public List<MeasureDto> selectByComponentUuidsAndMetricKeys(DbSession dbSession, Collection<String> largeComponentUuids, Collection<String> metricKeys) {
    if (largeComponentUuids.isEmpty() || metricKeys.isEmpty()) {
      return Collections.emptyList();
    }

    return executeLargeInputs(
      largeComponentUuids,
      componentUuids -> mapper(dbSession).selectByComponentUuids(componentUuids)).stream()
      .map(measureDto -> {
        measureDto.getMetricValues().entrySet().removeIf(entry -> !metricKeys.contains(entry.getKey()));
        return measureDto;
      })
      .filter(measureDto -> !measureDto.getMetricValues().isEmpty())
      .toList();
  }

  public Set<MeasureHash> selectBranchMeasureHashes(DbSession dbSession, String branchUuid) {
    return mapper(dbSession).selectBranchMeasureHashes(branchUuid);
  }

  private static MeasureMapper mapper(DbSession dbSession) {
    return dbSession.getMapper(MeasureMapper.class);
  }
}
