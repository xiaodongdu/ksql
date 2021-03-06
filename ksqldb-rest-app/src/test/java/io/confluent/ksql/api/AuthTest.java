/*
 * Copyright 2020 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import io.confluent.ksql.api.auth.ApiServerConfig;
import io.confluent.ksql.api.auth.AuthenticationPlugin;
import io.confluent.ksql.api.server.Server;
import io.confluent.ksql.api.server.ServerUtils;
import io.confluent.ksql.api.utils.InsertsResponse;
import io.confluent.ksql.api.utils.QueryResponse;
import io.confluent.ksql.security.KsqlAuthorizationProvider;
import io.confluent.ksql.security.KsqlSecurityExtension;
import io.confluent.ksql.security.KsqlUserContextProvider;
import io.confluent.ksql.test.util.TestBasicJaasConfig;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.VertxCompletableFuture;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(CoreApiTestRunner.class)
public class AuthTest extends ApiTest {

  protected static final Logger log = LoggerFactory.getLogger(AuthTest.class);

  private static final String PROPS_JAAS_REALM = "KsqlServer-Props";
  private static final String KSQL_RESOURCE = "ksql-user";
  private static final String OTHER_RESOURCE = "not-ksql";
  private static final String USER_WITH_ACCESS = "harry";
  private static final String USER_WITH_ACCESS_PWD = "changeme";
  private static final String USER_WITHOUT_ACCESS = "john";
  private static final String USER_WITHOUT_ACCESS_PWD = "bad";
  private static final String USER_WITH_INCORRECT_ROLE = "maud";
  private static final String USER_WITH_INCORRECT_ROLE_PWD = "1234";

  @ClassRule
  public static final TestBasicJaasConfig JAAS_CONFIG = TestBasicJaasConfig
      .builder(PROPS_JAAS_REALM)
      .addUser(USER_WITH_ACCESS, USER_WITH_ACCESS_PWD, KSQL_RESOURCE)
      .addUser(USER_WITH_INCORRECT_ROLE, USER_WITH_INCORRECT_ROLE_PWD, OTHER_RESOURCE)
      .build();

  private volatile KsqlAuthorizationProvider authorizationProvider;
  private volatile AuthenticationPlugin securityHandlerPlugin;

  @Override
  protected ApiServerConfig createServerConfig() {
    ApiServerConfig config = super.createServerConfig();
    Map<String, Object> origs = config.originals();
    origs.put(
        ApiServerConfig.AUTHENTICATION_METHOD_CONFIG,
        ApiServerConfig.AUTHENTICATION_METHOD_BASIC);
    origs.put(
        ApiServerConfig.AUTHENTICATION_REALM_CONFIG,
        PROPS_JAAS_REALM
    );
    origs.put(
        ApiServerConfig.AUTHENTICATION_ROLES_CONFIG,
        KSQL_RESOURCE
    );
    return new ApiServerConfig(origs);
  }

  @Override
  protected void createServer(ApiServerConfig serverConfig) {
    server = new Server(vertx, serverConfig, testEndpoints, false,
        new KsqlSecurityExtension() {
          @Override
          public void initialize(final KsqlConfig ksqlConfig) {
          }

          @Override
          public Optional<KsqlAuthorizationProvider> getAuthorizationProvider() {
            return Optional.ofNullable(authorizationProvider);
          }

          @Override
          public Optional<KsqlUserContextProvider> getUserContextProvider() {
            return Optional.empty();
          }

          @Override
          public void close() {
          }
        },
        Optional.ofNullable(securityHandlerPlugin));
    server.start();
  }


  @Override
  protected HttpResponse<Buffer> sendRequest(final WebClient client, final String uri,
      final Buffer requestBody)
      throws Exception {
    return sendRequestWithCreds(client, uri, requestBody, USER_WITH_ACCESS, USER_WITH_ACCESS_PWD);
  }

  @Override
  protected void sendRequest(
      final WebClient client,
      final String uri,
      final Consumer<HttpRequest<Buffer>> requestSender) {
    requestSender.accept(
        client.post(uri)
            .basicAuthentication(USER_WITH_ACCESS, USER_WITH_ACCESS_PWD)
    );
  }

  @Test
  public void shouldFailQueryWithBadCredentials() throws Exception {
    shouldFailQuery(USER_WITHOUT_ACCESS, USER_WITHOUT_ACCESS_PWD, 401, "Unauthorized");
  }

  @Test
  public void shouldFailCloseQueryWithBadCredentials() throws Exception {
    shouldFailCloseQuery(USER_WITHOUT_ACCESS, USER_WITHOUT_ACCESS_PWD, 401, "Unauthorized");
  }

  @Test
  public void shouldFailInsertRequestWithBadCredentials() throws Exception {
    shouldFailInsertRequest(USER_WITHOUT_ACCESS, USER_WITHOUT_ACCESS_PWD, 401, "Unauthorized");
  }

  @Test
  public void shouldFailQueryWithNoCredentials() throws Exception {
    shouldFailQuery(null, null, 401, "Unauthorized");
  }

  @Test
  public void shouldFailCloseQueryWithNoCredentials() throws Exception {
    shouldFailCloseQuery(null, null, 401, "Unauthorized");
  }

  @Test
  public void shouldFailInsertRequestWithNoCredentials() throws Exception {
    shouldFailInsertRequest(null, null, 401, "Unauthorized");
  }

  @Test
  public void shouldFailQueryWithIncorrectRole() throws Exception {
    shouldFailQuery(USER_WITH_INCORRECT_ROLE, USER_WITH_INCORRECT_ROLE_PWD, 403, "Forbidden");
  }

  @Test
  public void shouldFailCloseQueryWithIncorrectRole() throws Exception {
    shouldFailCloseQuery(USER_WITH_INCORRECT_ROLE, USER_WITH_INCORRECT_ROLE_PWD, 403, "Forbidden");
  }

  @Test
  public void shouldFailInsertRequestWithIncorrectRole() throws Exception {
    shouldFailInsertRequest(USER_WITH_INCORRECT_ROLE, USER_WITH_INCORRECT_ROLE_PWD, 403,
        "Forbidden");
  }

  @Test
  public void shouldExecutePullQueryWithApiSecurityContext() throws Exception {
    super.shouldExecutePullQuery();
    assertAuthorisedSecurityContext(USER_WITH_ACCESS);
  }

  @Test
  public void shouldStreamInsertsWithApiSecurityContext() throws Exception {
    super.shouldStreamInserts();
    assertAuthorisedSecurityContext(USER_WITH_ACCESS);
  }

  @Test
  public void shouldCloseQueryWithApiSecurityContext() throws Exception {
    super.shouldCloseQuery();
    assertAuthorisedSecurityContext(USER_WITH_ACCESS);
  }

  @Test
  public void shouldAllowQueryWithPermissionCheck() throws Exception {
    shouldAllowAccessWithPermissionCheck(USER_WITH_ACCESS, "POST",
        "/query-stream", super::shouldExecutePullQuery);
  }

  @Test
  public void shouldAllowInsertsWithPermissionCheck() throws Exception {
    shouldAllowAccessWithPermissionCheck(USER_WITH_ACCESS, "POST",
        "/inserts-stream", super::shouldInsertWithAcksStream);
  }

  @Test
  public void shouldAllowCloseQueryWithPermissionCheck() throws Exception {
    shouldAllowAccessWithPermissionCheck(USER_WITH_ACCESS, "POST",
        "/close-query", super::shouldCloseQuery);
  }

  @Test
  public void shouldNotAllowQueryIfPermissionCheckThrowsException() throws Exception {
    shouldNotAllowAccessIfPermissionCheckThrowsException(
        () -> shouldFailQuery(USER_WITH_ACCESS, USER_WITH_ACCESS_PWD, 403, "Forbidden"));
  }

  @Test
  public void shouldNotAllowInsertsIfPermissionCheckThrowsException() throws Exception {
    shouldNotAllowAccessIfPermissionCheckThrowsException(
        () -> shouldFailInsertRequest(USER_WITH_ACCESS, USER_WITH_ACCESS_PWD, 403, "Forbidden"));
  }

  @Test
  public void shouldNotAllowCloseQueryIfPermissionCheckThrowsException() throws Exception {
    shouldNotAllowAccessIfPermissionCheckThrowsException(
        () -> shouldFailCloseQuery(USER_WITH_ACCESS, USER_WITH_ACCESS_PWD, 403, "Forbidden"));
  }

  @Test
  public void shouldAllowQueryWithSecurityPlugin() throws Exception {
    shouldAuthenticateWithSecurityPlugin(USER_WITHOUT_ACCESS, super::shouldExecutePullQuery, true);
  }

  @Test
  public void shouldNotAllowQueryWithSecurityPlugin() throws Exception {
    shouldAuthenticateWithSecurityPlugin(USER_WITHOUT_ACCESS,
        () -> shouldFailQuery(USER_WITH_ACCESS, USER_WITH_ACCESS_PWD, 401, "Unauthorized"), false);
  }

  @Test
  public void shouldAllowInsertsWithSecurityPlugin() throws Exception {
    shouldAuthenticateWithSecurityPlugin(USER_WITHOUT_ACCESS, super::shouldInsertWithAcksStream,
        true);
  }

  @Test
  public void shouldNotAllowInsertsWithSecurityPlugin() throws Exception {
    shouldAuthenticateWithSecurityPlugin(USER_WITHOUT_ACCESS,
        () -> shouldFailInsertRequest(USER_WITH_ACCESS, USER_WITH_ACCESS_PWD, 401, "Unauthorized"),
        false);
  }

  @Test
  public void shouldAllowCloseQueryWithSecurityPlugin() throws Exception {
    shouldAuthenticateWithSecurityPlugin(USER_WITHOUT_ACCESS, super::shouldCloseQuery, true);
  }

  @Test
  public void shouldNotAllowCloseQueryWithSecurityPlugin() throws Exception {
    shouldAuthenticateWithSecurityPlugin(USER_WITHOUT_ACCESS,
        () -> shouldFailCloseQuery(USER_WITH_ACCESS, USER_WITH_ACCESS_PWD, 401, "Unauthorized"),
        false);
  }

  private void shouldFailQuery(final String username, final String password,
      final int expectedStatus, final String expectedMessage) throws Exception {
    // When
    HttpResponse<Buffer> response = sendRequestWithCreds(
        "/query-stream",
        DEFAULT_PUSH_QUERY_REQUEST_BODY.toBuffer(),
        username,
        password
    );

    // Then
    assertThat(response.statusCode(), is(expectedStatus));

    QueryResponse queryResponse = new QueryResponse(response.bodyAsString());
    validateError(expectedStatus, expectedMessage, queryResponse.responseObject);
  }

  private void shouldFailCloseQuery(final String username, final String password,
      final int expectedStatus, final String expectedMessage) throws Exception {
    // Given
    JsonObject requestBody = new JsonObject().put("queryId", "foo");

    // When
    HttpResponse<Buffer> response = sendRequestWithCreds(
        "/close-query",
        requestBody.toBuffer(),
        username,
        password
    );

    // Then
    assertThat(response.statusCode(), is(expectedStatus));

    QueryResponse queryResponse = new QueryResponse(response.bodyAsString());
    validateError(expectedStatus, expectedMessage, queryResponse.responseObject);
  }

  private void shouldFailInsertRequest(final String username, final String password,
      int expectedStatus, final String expectedMessage) throws Exception {
    // Given
    JsonObject params = new JsonObject().put("target", "test-stream");
    Buffer requestBody = Buffer.buffer();
    requestBody.appendBuffer(params.toBuffer()).appendString("\n");
    for (JsonObject row : DEFAULT_INSERT_ROWS) {
      requestBody.appendBuffer(row.toBuffer()).appendString("\n");
    }

    // When
    HttpResponse<Buffer> response = sendRequestWithCreds(
        "/inserts-stream",
        requestBody,
        username,
        password
    );

    // Then
    assertThat(response.statusCode(), is(expectedStatus));

    InsertsResponse insertsResponse = new InsertsResponse(response.bodyAsString());
    validateError(expectedStatus, expectedMessage, insertsResponse.error);
  }

  private HttpResponse<Buffer> sendRequestWithCreds(
      final String uri,
      final Buffer requestBody,
      final String username,
      final String password
  ) throws Exception {
    return sendRequestWithCreds(client, uri, requestBody, username, password);
  }

  // auth header is omitted if username and password are null
  private static HttpResponse<Buffer> sendRequestWithCreds(
      final WebClient client,
      final String uri,
      final Buffer requestBody,
      final String username,
      final String password
  ) throws Exception {
    VertxCompletableFuture<HttpResponse<Buffer>> requestFuture = new VertxCompletableFuture<>();
    HttpRequest<Buffer> request = client.post(uri);
    if (username != null || password != null) {
      request = request.basicAuthentication(username, password);
    }
    request.sendBuffer(requestBody, requestFuture);
    return requestFuture.get();
  }

  private void assertAuthorisedSecurityContext(String username) {
    assertThat(testEndpoints.getLastApiSecurityContext(), is(notNullValue()));
    assertThat(testEndpoints.getLastApiSecurityContext().getPrincipal().isPresent(), is(true));
    assertThat(testEndpoints.getLastApiSecurityContext().getPrincipal().get().getName(),
        is(username));
  }

  private void shouldAuthenticateWithSecurityPlugin(final String expectedUser,
      final ExceptionThrowingRunnable action, final boolean authenticate) throws Exception {
    stopServer();
    stopClient();

    final AtomicBoolean handlerCalled = new AtomicBoolean();

    this.securityHandlerPlugin = new AuthenticationPlugin() {
      @Override
      public void configure(final Map<String, ?> map) {
      }

      @Override
      public CompletableFuture<Principal> handleAuth(final RoutingContext routingContext,
          final WorkerExecutor workerExecutor) {
        handlerCalled.set(true);
        if (authenticate) {
          return CompletableFuture.completedFuture(new StringPrincipal(expectedUser));
        } else {
          ServerUtils.handleError(routingContext.response(), 401, 401, "Unauthorized");
          return CompletableFuture.completedFuture(null);
        }
      }
    };

    createServer(createServerConfig());
    client = createClient();
    action.run();
    assertThat(handlerCalled.get(), is(true));
  }

  private void shouldAllowAccessWithPermissionCheck(final String expectedUser,
      final String expectedMethod, final String expectedPath,
      final ExceptionThrowingRunnable action) throws Exception {
    stopServer();
    stopClient();
    AtomicReference<Principal> principalAtomicReference = new AtomicReference<>();
    AtomicReference<String> methodAtomicReference = new AtomicReference<>();
    AtomicReference<String> pathAtomicReference = new AtomicReference<>();
    this.authorizationProvider = (user, method, path) -> {
      principalAtomicReference.set(user);
      methodAtomicReference.set(method);
      pathAtomicReference.set(path);
    };
    createServer(createServerConfig());
    client = createClient();
    action.run();
    assertThat(principalAtomicReference.get().getName(), is(expectedUser));
    assertThat(methodAtomicReference.get(), is(expectedMethod));
    assertThat(pathAtomicReference.get(), is(expectedPath));
  }

  private void shouldNotAllowAccessIfPermissionCheckThrowsException(
      ExceptionThrowingRunnable runnable) throws Exception {
    stopServer();
    stopClient();
    this.authorizationProvider = (user, method, path) -> {
      throw new KsqlException("Forbidden");
    };
    createServer(createServerConfig());
    client = createClient();
    runnable.run();
  }

  private interface ExceptionThrowingRunnable {

    void run() throws Exception;
  }

  private static class StringPrincipal implements Principal {

    private final String name;

    public StringPrincipal(final String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }
  }


}
