// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.grid.graphql;

import org.junit.Before;
import org.junit.Test;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.events.local.GuavaEventBus;
import org.openqa.selenium.grid.data.CreateSessionRequest;
import org.openqa.selenium.grid.data.CreateSessionResponse;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.grid.data.Slot;
import org.openqa.selenium.grid.distributor.Distributor;
import org.openqa.selenium.grid.distributor.local.LocalDistributor;
import org.openqa.selenium.grid.node.ActiveSession;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.grid.node.SessionFactory;
import org.openqa.selenium.grid.node.local.LocalNode;
import org.openqa.selenium.grid.security.Secret;
import org.openqa.selenium.grid.sessionmap.SessionMap;
import org.openqa.selenium.grid.sessionmap.local.LocalSessionMap;
import org.openqa.selenium.grid.sessionqueue.NewSessionQueuer;
import org.openqa.selenium.grid.sessionqueue.local.LocalNewSessionQueue;
import org.openqa.selenium.grid.sessionqueue.local.LocalNewSessionQueuer;
import org.openqa.selenium.grid.testing.TestSessionFactory;
import org.openqa.selenium.internal.Either;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.NewSessionPayload;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.tracing.DefaultTestTracer;
import org.openqa.selenium.remote.tracing.Tracer;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Wait;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.openqa.selenium.json.Json.MAP_TYPE;
import static org.openqa.selenium.remote.http.Contents.utf8String;
import static org.openqa.selenium.remote.http.HttpMethod.GET;
import static org.openqa.selenium.remote.http.HttpMethod.POST;

import com.google.common.collect.ImmutableMap;

public class GraphqlHandlerTest {

  private final Secret registrationSecret = new Secret("stilton");
  private final URI publicUri = new URI("http://example.com/grid-o-matic");
  private Distributor distributor;
  private Tracer tracer;
  private EventBus events;
  private ImmutableCapabilities caps;
  private ImmutableCapabilities stereotype;
  private NewSessionPayload payload;
  private final Wait<Object> wait = new FluentWait<>(new Object()).withTimeout(Duration.ofSeconds(5));

  public GraphqlHandlerTest() throws URISyntaxException {
  }

  @Before
  public void setupGrid() {
    tracer = DefaultTestTracer.createTracer();
    events = new GuavaEventBus();
    HttpClient.Factory clientFactory = HttpClient.Factory.createDefault();

    SessionMap sessions = new LocalSessionMap(tracer, events);
    stereotype = new ImmutableCapabilities("browserName", "cheese");
    caps = new ImmutableCapabilities("browserName", "cheese");
    payload = NewSessionPayload.create(caps);

    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      events,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    NewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, events, localNewSessionQueue);

    distributor = new LocalDistributor(
      tracer,
      events,
      clientFactory,
      sessions,
      queuer,
      registrationSecret);
  }

  @Test
  public void shouldBeAbleToGetGridUri() {
    GraphqlHandler handler = new GraphqlHandler(tracer, distributor, publicUri);

    Map<String, Object> topLevel = executeQuery(handler, "{ grid { uri } }");

    assertThat(topLevel).isEqualTo(
      singletonMap(
        "data", singletonMap(
          "grid", singletonMap(
            "uri", publicUri.toString()))));
  }

  @Test
  public void shouldReturnAnEmptyListForNodesIfNoneAreRegistered() {
    GraphqlHandler handler = new GraphqlHandler(tracer, distributor, publicUri);

    Map<String, Object> topLevel = executeQuery(handler, "{ grid { nodes { uri } } }");

    assertThat(topLevel).describedAs(topLevel.toString()).isEqualTo(
      singletonMap(
        "data", singletonMap(
          "grid", singletonMap(
            "nodes", Collections.emptyList()))));
  }

  @Test
  public void shouldBeAbleToGetUrlsOfAllNodes() throws URISyntaxException {
    Capabilities stereotype = new ImmutableCapabilities("cheese", "stilton");
    String nodeUri = "http://localhost:5556";
    Node node = LocalNode.builder(tracer, events, new URI(nodeUri), publicUri, registrationSecret)
      .add(stereotype, new SessionFactory() {
        @Override
        public Optional<ActiveSession> apply(CreateSessionRequest createSessionRequest) {
          return Optional.empty();
        }

        @Override
        public boolean test(Capabilities capabilities) {
          return false;
        }
      })
      .build();
    distributor.add(node);
    wait.until(obj -> distributor.getStatus().hasCapacity());

    GraphqlHandler handler = new GraphqlHandler(tracer, distributor, publicUri);
    Map<String, Object> topLevel = executeQuery(handler, "{ grid { nodes { uri } } }");

    assertThat(topLevel).describedAs(topLevel.toString()).isEqualTo(
      singletonMap(
        "data", singletonMap(
          "grid", singletonMap(
            "nodes", singletonList(singletonMap("uri", nodeUri))))));
  }

  @Test
  public void shouldBeAbleToGetSessionInfo() throws URISyntaxException {
    String nodeUrl = "http://localhost:5556";
    URI nodeUri = new URI(nodeUrl);

    Node node = LocalNode.builder(tracer, events, nodeUri, publicUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, caps) -> new org.openqa.selenium.grid.data.Session(
        id,
        nodeUri,
        stereotype,
        caps,
        Instant.now()))).build();

    distributor.add(node);
    wait.until(obj -> distributor.getStatus().hasCapacity());

    Either<SessionNotCreatedException, CreateSessionResponse> response =
      distributor.newSession(createRequest(payload));
    if (response.isRight()) {
      Session session = response.right().getSession();

      assertThat(session).isNotNull();
      String sessionId = session.getId().toString();

      Set<Slot> slots = distributor.getStatus().getNodes().stream().findFirst().get().getSlots();

      Slot slot = slots.stream().findFirst().get();

      org.openqa.selenium.grid.graphql.Session graphqlSession =
        new org.openqa.selenium.grid.graphql.Session(
          sessionId,
          session.getCapabilities(),
          session.getStartTime(),
          session.getUri(),
          node.getId().toString(),
          node.getUri(),
          slot);
      String query = String.format(
        "{ session (id: \"%s\") { id, capabilities, startTime, uri } }", sessionId);

      GraphqlHandler handler = new GraphqlHandler(tracer, distributor, publicUri);
      Map<String, Object> result = executeQuery(handler, query);

      assertThat(result).describedAs(result.toString()).isEqualTo(
        singletonMap(
          "data", singletonMap(
            "session", ImmutableMap.of(
              "id", sessionId,
              "capabilities", graphqlSession.getCapabilities(),
              "startTime", graphqlSession.getStartTime(),
              "uri", graphqlSession.getUri().toString()))));
    } else {
      fail("Session creation failed", response.left());
    }
  }

  @Test
  public void shouldBeAbleToGetNodeInfoForSession() throws URISyntaxException {
    String nodeUrl = "http://localhost:5556";
    URI nodeUri = new URI(nodeUrl);

    Node node = LocalNode.builder(tracer, events, nodeUri, publicUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, caps) -> new org.openqa.selenium.grid.data.Session(
        id,
        nodeUri,
        stereotype,
        caps,
        Instant.now()))).build();

    distributor.add(node);
    wait.until(obj -> distributor.getStatus().hasCapacity());

    Either<SessionNotCreatedException, CreateSessionResponse> response =
      distributor.newSession(createRequest(payload));

    if (response.isRight()) {
      Session session = response.right().getSession();

      assertThat(session).isNotNull();
      String sessionId = session.getId().toString();

      Set<Slot> slots = distributor.getStatus().getNodes().stream().findFirst().get().getSlots();

      Slot slot = slots.stream().findFirst().get();

      org.openqa.selenium.grid.graphql.Session graphqlSession =
        new org.openqa.selenium.grid.graphql.Session(
          sessionId,
          session.getCapabilities(),
          session.getStartTime(),
          session.getUri(),
          node.getId().toString(),
          node.getUri(),
          slot);
      String query = String.format("{ session (id: \"%s\") { nodeId, nodeUri } }", sessionId);

      GraphqlHandler handler = new GraphqlHandler(tracer, distributor, publicUri);
      Map<String, Object> result = executeQuery(handler, query);

      assertThat(result).describedAs(result.toString()).isEqualTo(
        singletonMap(
          "data", singletonMap(
            "session", ImmutableMap.of(
              "nodeId", graphqlSession.getNodeId(),
              "nodeUri", graphqlSession.getNodeUri().toString()))));
    } else {
      fail("Session creation failed", response.left());
    }
  }

  @Test
  public void shouldBeAbleToGetSlotInfoForSession() throws URISyntaxException {
    String nodeUrl = "http://localhost:5556";
    URI nodeUri = new URI(nodeUrl);

    Node node = LocalNode.builder(tracer, events, nodeUri, publicUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, caps) -> new org.openqa.selenium.grid.data.Session(
        id,
        nodeUri,
        stereotype,
        caps,
        Instant.now()))).build();

    distributor.add(node);
    wait.until(obj -> distributor.getStatus().hasCapacity());

    Either<SessionNotCreatedException, CreateSessionResponse> response =
      distributor.newSession(createRequest(payload));

    if (response.isRight()) {
      Session session = response.right().getSession();

      assertThat(session).isNotNull();
      String sessionId = session.getId().toString();

      Set<Slot> slots = distributor.getStatus().getNodes().stream().findFirst().get().getSlots();

      Slot slot = slots.stream().findFirst().get();

      org.openqa.selenium.grid.graphql.Session graphqlSession =
        new org.openqa.selenium.grid.graphql.Session(
          sessionId,
          session.getCapabilities(),
          session.getStartTime(),
          session.getUri(),
          node.getId().toString(),
          node.getUri(),
          slot);

      org.openqa.selenium.grid.graphql.Slot graphqlSlot = graphqlSession.getSlot();

      String query = String.format(
        "{ session (id: \"%s\") { slot { id, stereotype, lastStarted } } }", sessionId);

      GraphqlHandler handler = new GraphqlHandler(tracer, distributor, publicUri);
      Map<String, Object> result = executeQuery(handler, query);

      assertThat(result).describedAs(result.toString()).isEqualTo(
        singletonMap(
          "data", singletonMap(
            "session", singletonMap(
              "slot", ImmutableMap.of(
                "id", graphqlSlot.getId(),
                "stereotype", graphqlSlot.getStereotype(),
                "lastStarted", graphqlSlot.getLastStarted())))));
    } else {
      fail("Session creation failed", response.left());
    }
  }

  @Test
  public void shouldBeAbleToGetSessionDuration() throws URISyntaxException {
    String nodeUrl = "http://localhost:5556";
    URI nodeUri = new URI(nodeUrl);

    Node node = LocalNode.builder(tracer, events, nodeUri, publicUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, caps) -> new org.openqa.selenium.grid.data.Session(
        id,
        nodeUri,
        stereotype,
        caps,
        Instant.now()))).build();

    distributor.add(node);
    wait.until(obj -> distributor.getStatus().hasCapacity());

    Either<SessionNotCreatedException, CreateSessionResponse> response =
      distributor.newSession(createRequest(payload));

    if (response.isRight()) {
      Session session = response.right().getSession();

      assertThat(session).isNotNull();
      String sessionId = session.getId().toString();

      String query = String.format("{ session (id: \"%s\") { sessionDurationMillis } }", sessionId);

      GraphqlHandler handler = new GraphqlHandler(tracer, distributor, publicUri);
      Map<String, Object> result = executeQuery(handler, query);

      assertThat(result)
        .containsOnlyKeys("data")
        .extracting("data").asInstanceOf(MAP).containsOnlyKeys("session")
        .extracting("session").asInstanceOf(MAP).containsOnlyKeys("sessionDurationMillis");
    } else {
      fail("Session creation failed", response.left());
    }
  }

  @Test
  public void shouldThrowExceptionWhenSessionNotFound() throws URISyntaxException {
    String nodeUrl = "http://localhost:5556";
    URI nodeUri = new URI(nodeUrl);

    Node node = LocalNode.builder(tracer, events, nodeUri, publicUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, caps) -> new org.openqa.selenium.grid.data.Session(
        id,
        nodeUri,
        stereotype,
        caps,
        Instant.now()))).build();

    distributor.add(node);
    wait.until(obj -> distributor.getStatus().hasCapacity());

    String randomSessionId = UUID.randomUUID().toString();
    String query = "{ session (id: \"" + randomSessionId + "\") { sessionDurationMillis } }";

    GraphqlHandler handler = new GraphqlHandler(tracer, distributor, publicUri);
    Map<String, Object> result = executeQuery(handler, query);
    assertThat(result)
      .containsEntry("data", null)
      .containsKey("errors")
      .extracting("errors").asInstanceOf(LIST).isNotEmpty()
      .element(0).asInstanceOf(MAP).containsKey("extensions")
      .extracting("extensions").asInstanceOf(MAP).containsKey("sessionId")
      .extracting("sessionId").isEqualTo(randomSessionId);
  }

  @Test
  public void shouldThrowExceptionWhenSessionIsEmpty() throws URISyntaxException {
    String nodeUrl = "http://localhost:5556";
    URI nodeUri = new URI(nodeUrl);

    Node node = LocalNode.builder(tracer, events, nodeUri, publicUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, caps) -> new org.openqa.selenium.grid.data.Session(
        id,
        nodeUri,
        stereotype,
        caps,
        Instant.now()))).build();

    distributor.add(node);
    wait.until(obj -> distributor.getStatus().hasCapacity());

    String query = "{ session (id: \"\") { sessionDurationMillis } }";

    GraphqlHandler handler = new GraphqlHandler(tracer, distributor, publicUri);
    Map<String, Object> result = executeQuery(handler, query);
    assertThat(result)
      .containsEntry("data", null)
      .containsKey("errors")
      .extracting("errors").asInstanceOf(LIST).isNotEmpty();
  }

  private Map<String, Object> executeQuery(HttpHandler handler, String query) {
    HttpResponse res = handler.execute(
      new HttpRequest(GET, "/graphql")
        .setContent(Contents.asJson(singletonMap("query", query))));

    return new Json().toType(Contents.string(res), MAP_TYPE);
  }

  private HttpRequest createRequest(NewSessionPayload payload) {
    StringBuilder builder = new StringBuilder();
    try {
      payload.writeTo(builder);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    HttpRequest request = new HttpRequest(POST, "/se/grid/distributor/session");
    request.setContent(utf8String(builder.toString()));

    return request;
  }
}
