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
package org.sonar.server.tester;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.sonar.db.component.BranchDto;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.entity.EntityDto;
import org.sonar.db.permission.GlobalPermission;
import org.sonar.db.permission.ProjectPermission;
import org.sonar.db.portfolio.PortfolioDto;
import org.sonar.db.project.ProjectDto;
import org.sonar.db.user.GroupDto;
import org.sonar.db.user.UserDto;
import org.sonar.server.user.UserSession;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * {@code UserSessionRule} is intended to be used as a {@link org.junit.Rule} to easily manage {@link UserSession} in
 * unit tests.
 * <p>
 * It can be used as a {@link org.junit.ClassRule} but be careful not to modify its states from inside tests methods
 * unless you purposely want to have side effects between each tests.
 * </p>
 * <p>
 * One can define user session behavior which should apply on all tests directly on the property, eg.:
 * <pre>
 * {@literal @}Rule
 * public UserSessionRule userSession = UserSessionRule.standalone().login("admin").setOrganizationPermissions(OrganizationPermissions.SYSTEM_ADMIN);
 * </pre>
 * </p>
 * <p>
 * Behavior defined at property-level can obviously be override at test method level. For example, one could define
 * all methods to use an authenticated session such as presented above but can easily overwrite that behavior in a
 * specific test method as follow:
 * <pre>
 * {@literal @}Test
 * public void test_method() {
 *   userSession.standalone();
 *   {@literal [...]}
 * }
 * </pre>
 * </p>
 * <p>
 * {@code UserSessionRule}, emulates by default an anonymous
 * session. Therefore, call {@code UserSessionRule.standalone()} is equivalent to calling
 * {@code UserSessionRule.standalone().anonymous()}.
 * </p>
 * <p>
 * To emulate an identified user, either use method {@link #logIn(String)} if you want to specify the user's login, or
 * method {@link #logIn()} which will do the same but using the value of {@link #DEFAULT_LOGIN} as the user's login
 * (use the latest override if you don't care about the actual value of the login, it will save noise in your test).
 * </p>
 */
public class UserSessionRule implements TestRule, UserSession, BeforeTestExecutionCallback, AfterTestExecutionCallback {
  private static final String DEFAULT_LOGIN = "default_login";

  private AbstractMockUserSession<?> currentUserSession;

  private UserSessionRule() {
    anonymous();
  }

  public static UserSessionRule standalone() {
    return new UserSessionRule();
  }

  /**
   * Log in with the default login {@link #DEFAULT_LOGIN}
   */
  public UserSessionRule logIn() {
    return logIn(DEFAULT_LOGIN);
  }

  /**
   * Log in with the specified login
   */
  public UserSessionRule logIn(String login) {
    setCurrentUserSession(new MockUserSession(login));
    return this;
  }

  /**
   * Log in with the specified login
   */
  public UserSessionRule logIn(UserDto userDto) {
    setCurrentUserSession(new MockUserSession(userDto));
    return this;
  }

  /**
   * Disconnect/go anonymous
   */
  public UserSessionRule anonymous() {
    setCurrentUserSession(new AnonymousMockUserSession());
    return this;
  }

  public UserSessionRule setSystemAdministrator() {
    ensureMockUserSession().setSystemAdministrator(true);
    return this;
  }

  public UserSessionRule setNonSystemAdministrator() {
    ensureMockUserSession().setSystemAdministrator(false);
    return this;
  }

  public UserSessionRule setExternalIdentity(IdentityProvider identityProvider, ExternalIdentity externalIdentity) {
    ensureMockUserSession().setExternalIdentity(identityProvider, externalIdentity);
    return this;
  }

  public UserSessionRule setInternalIdentity() {
    ensureMockUserSession().setInternalIdentity();
    return this;
  }

  @Override
  public Statement apply(Statement statement, Description description) {
    return this.statement(statement);
  }

  private Statement statement(final Statement base) {
    return new Statement() {
      public void evaluate() throws Throwable {
        UserSessionRule.this.before();

        try {
          base.evaluate();
        } finally {
          UserSessionRule.this.after();
        }

      }
    };
  }

  protected void before() {
    setCurrentUserSession(currentUserSession);
  }

  protected void after() {
    this.currentUserSession = null;
  }

  @Override
  public void beforeTestExecution(ExtensionContext context) {
    before();
  }

  @Override
  public void afterTestExecution(ExtensionContext context) {
    after();
  }

  public void set(AbstractMockUserSession<?> userSession) {
    checkNotNull(userSession);
    setCurrentUserSession(userSession);
  }

  public UserSessionRule registerPortfolios(ComponentDto... portfolios) {
    ensureAbstractMockUserSession().registerComponents(portfolios);
    return this;
  }

  public UserSessionRule registerPortfolios(PortfolioDto... portfolioDtos) {
    ensureAbstractMockUserSession().registerPortfolios(portfolioDtos);
    return this;
  }

  public UserSessionRule registerProjects(ProjectDto... projectDtos) {
    ensureAbstractMockUserSession().registerProjects(projectDtos);
    return this;
  }

  public UserSessionRule registerApplication(ProjectDto application, ProjectDto... appProjects) {
    ensureAbstractMockUserSession().registerApplication(application, appProjects);
    return this;
  }

  public UserSessionRule addProjectPermission(ProjectPermission projectPermission, ComponentDto... components) {
    ensureAbstractMockUserSession().addProjectPermission(projectPermission, components);
    return this;
  }

  public UserSessionRule addPortfolioPermission(ProjectPermission projectPermission, ComponentDto... components) {
    ensureAbstractMockUserSession().addProjectPermission(projectPermission, components);
    return this;
  }

  public UserSessionRule addProjectBranchMapping(String projectUuid, ComponentDto... branchComponents) {
    ensureAbstractMockUserSession().addProjectBranchMapping(projectUuid, branchComponents);
    return this;
  }

  public UserSessionRule registerBranches(BranchDto... branchDtos) {
    ensureAbstractMockUserSession().registerBranches(branchDtos);
    return this;
  }

  public UserSessionRule addProjectPermission(ProjectPermission projectPermission, ProjectDto... projectDto) {
    ensureAbstractMockUserSession().addProjectPermission(projectPermission, projectDto);
    return this;
  }

  public UserSessionRule addPortfolioPermission(ProjectPermission portfolioPermission, PortfolioDto... portfolioDto) {
    ensureAbstractMockUserSession().addPortfolioPermission(portfolioPermission, portfolioDto);
    return this;
  }

  public UserSessionRule addPermission(GlobalPermission permission) {
    ensureAbstractMockUserSession().addPermission(permission);
    return this;
  }

  /**
   * Groups that user is member of. User must be logged in. An exception
   * is thrown if session is anonymous.
   */
  public UserSessionRule setGroups(GroupDto... groups) {
    ensureMockUserSession().setGroups(groups);
    return this;
  }

  public UserSessionRule setName(@Nullable String s) {
    ensureMockUserSession().setName(s);
    return this;
  }

  private AbstractMockUserSession ensureAbstractMockUserSession() {
    checkState(currentUserSession instanceof AbstractMockUserSession, "rule state can not be changed if a UserSession has explicitly been provided");
    return (AbstractMockUserSession) currentUserSession;
  }

  private MockUserSession ensureMockUserSession() {
    checkState(currentUserSession instanceof MockUserSession, "rule state can not be changed if a UserSession has explicitly been provided");
    return (MockUserSession) currentUserSession;
  }

  private void setCurrentUserSession(AbstractMockUserSession<?> userSession) {
    this.currentUserSession = Preconditions.checkNotNull(userSession);
  }

  @Override
  public boolean hasComponentPermission(ProjectPermission permission, ComponentDto component) {
    return currentUserSession.hasComponentPermission(permission, component);
  }

  @Override
  public boolean hasEntityPermission(ProjectPermission permission, EntityDto entity) {
    return currentUserSession.hasEntityPermission(permission, entity.getUuid());
  }

  @Override
  public boolean hasEntityPermission(ProjectPermission permission, String entityUuid) {
    return currentUserSession.hasEntityPermission(permission, entityUuid);
  }

  @Override
  public boolean hasChildProjectsPermission(ProjectPermission permission, ComponentDto component) {
    return currentUserSession.hasChildProjectsPermission(permission, component);
  }

  @Override
  public boolean hasChildProjectsPermission(ProjectPermission permission, EntityDto application) {
    return currentUserSession.hasChildProjectsPermission(permission, application);
  }

  @Override
  public boolean hasPortfolioChildProjectsPermission(ProjectPermission permission, ComponentDto component) {
    return currentUserSession.hasPortfolioChildProjectsPermission(permission, component);
  }

  @Override
  public boolean hasComponentUuidPermission(ProjectPermission permission, String componentUuid) {
    return currentUserSession.hasComponentUuidPermission(permission, componentUuid);
  }

  @Override
  public List<ComponentDto> keepAuthorizedComponents(ProjectPermission permission, Collection<ComponentDto> components) {
    return currentUserSession.keepAuthorizedComponents(permission, components);
  }

  @Override
  public <T extends EntityDto> List<T> keepAuthorizedEntities(ProjectPermission permission, Collection<T> entities) {
    return currentUserSession.keepAuthorizedEntities(permission, entities);
  }

  @Override
  @CheckForNull
  public String getLogin() {
    return currentUserSession.getLogin();
  }

  @Override
  @CheckForNull
  public String getUuid() {
    return currentUserSession.getUuid();
  }

  @Override
  @CheckForNull
  public String getName() {
    return currentUserSession.getName();
  }

  @Override
  @CheckForNull
  public Long getLastSonarlintConnectionDate() {
    return currentUserSession.getLastSonarlintConnectionDate();
  }

  @Override
  public Collection<GroupDto> getGroups() {
    return currentUserSession.getGroups();
  }

  @Override
  public boolean shouldResetPassword() {
    return currentUserSession.shouldResetPassword();
  }

  @Override
  public Optional<IdentityProvider> getIdentityProvider() {
    return currentUserSession.getIdentityProvider();
  }

  @Override
  public Optional<ExternalIdentity> getExternalIdentity() {
    return currentUserSession.getExternalIdentity();
  }

  @Override
  public boolean isLoggedIn() {
    return currentUserSession.isLoggedIn();
  }

  @Override
  public UserSession checkLoggedIn() {
    currentUserSession.checkLoggedIn();
    return this;
  }

  @Override
  public boolean hasPermission(GlobalPermission permission) {
    return currentUserSession.hasPermission(permission);
  }

  @Override
  public UserSession checkPermission(GlobalPermission permission) {
    currentUserSession.checkPermission(permission);
    return this;
  }

  @Override
  public UserSession checkComponentPermission(ProjectPermission projectPermission, ComponentDto component) {
    currentUserSession.checkComponentPermission(projectPermission, component);
    return this;
  }

  @Override
  public UserSession checkEntityPermission(ProjectPermission projectPermission, EntityDto entity) {
    currentUserSession.checkEntityPermission(projectPermission, entity);
    return this;
  }

  @Override
  public UserSession checkEntityPermissionOrElseThrowResourceForbiddenException(ProjectPermission projectPermission, EntityDto entity) {
    currentUserSession.checkEntityPermissionOrElseThrowResourceForbiddenException(projectPermission, entity);
    return this;
  }

  @Override
  public UserSession checkChildProjectsPermission(ProjectPermission projectPermission, ComponentDto component) {
    currentUserSession.checkChildProjectsPermission(projectPermission, component);
    return this;
  }

  @Override
  public UserSession checkChildProjectsPermission(ProjectPermission projectPermission, EntityDto application) {
    currentUserSession.checkChildProjectsPermission(projectPermission, application);
    return this;
  }

  @Override
  public UserSession checkComponentUuidPermission(ProjectPermission permission, String componentUuid) {
    currentUserSession.checkComponentUuidPermission(permission, componentUuid);
    return this;
  }

  @Override
  public boolean isSystemAdministrator() {
    return currentUserSession.isSystemAdministrator();
  }

  @Override
  public UserSession checkIsSystemAdministrator() {
    currentUserSession.checkIsSystemAdministrator();
    return this;
  }

  @Override
  public boolean isActive() {
    return currentUserSession.isActive();
  }

  @Override
  public boolean isAuthenticatedBrowserSession() {
    return currentUserSession.isAuthenticatedBrowserSession();
  }

  public void flagSessionAsGui() {
    currentUserSession.flagAsBrowserSession();
  }
}
