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
package org.sonar.db;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class KeyLongValue {

  private String key;
  private Long value;

  public KeyLongValue() {
    // for MyBatis
  }

  public KeyLongValue(String key, Long value) {
    this.key = key;
    this.value = value;
  }

  public String getKey() {
    return key;
  }

  public Long getValue() {
    return value;
  }

  /**
   * This method does not keep order of list.
   */
  public static Map<String, Long> toMap(List<KeyLongValue> values) {
    return values
      .stream()
      .collect(Collectors.toMap(KeyLongValue::getKey, KeyLongValue::getValue));
  }
}
