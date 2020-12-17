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

package org.openqa.selenium.grid.distributor;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.assertj.core.api.AbstractAssert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.events.EventListener;
import org.openqa.selenium.events.EventName;
import org.openqa.selenium.events.local.GuavaEventBus;
import org.openqa.selenium.grid.data.Availability;
import org.openqa.selenium.grid.data.CreateSessionRequest;
import org.openqa.selenium.grid.data.CreateSessionResponse;
import org.openqa.selenium.grid.data.DistributorStatus;
import org.openqa.selenium.grid.data.NodeAddedEvent;
import org.openqa.selenium.grid.data.NodeRemovedEvent;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.grid.data.Slot;
import org.openqa.selenium.grid.distributor.local.LocalDistributor;
import org.openqa.selenium.grid.distributor.remote.RemoteDistributor;
import org.openqa.selenium.grid.node.HealthCheck;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.grid.node.local.LocalNode;
import org.openqa.selenium.grid.security.Secret;
import org.openqa.selenium.grid.sessionmap.SessionMap;
import org.openqa.selenium.grid.sessionmap.local.LocalSessionMap;
import org.openqa.selenium.grid.sessionqueue.local.LocalNewSessionQueue;
import org.openqa.selenium.grid.sessionqueue.local.LocalNewSessionQueuer;
import org.openqa.selenium.grid.testing.PassthroughHttpClient;
import org.openqa.selenium.grid.testing.TestSessionFactory;
import org.openqa.selenium.grid.web.CombinedHandler;
import org.openqa.selenium.internal.Either;
import org.openqa.selenium.net.PortProber;
import org.openqa.selenium.remote.Dialect;
import org.openqa.selenium.remote.NewSessionPayload;
import org.openqa.selenium.remote.SessionId;
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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.openqa.selenium.grid.data.Availability.DOWN;
import static org.openqa.selenium.grid.data.Availability.UP;
import static org.openqa.selenium.remote.http.Contents.utf8String;
import static org.openqa.selenium.remote.http.HttpMethod.POST;

public class DistributorTest {

  private static class EitherAssert<A, B> extends AbstractAssert<EitherAssert<A, B>, Either<A, B>> {
    public EitherAssert(Either<A, B> actual) {
      super(actual, EitherAssert.class);
    }

    public EitherAssert<A, B> isLeft() {
      isNotNull();
      if (actual.isRight()) {
        failWithMessage(
          "Expected Either to be left but it is right: %s", actual.right());
      }
      return this;
    }

    public EitherAssert<A, B> isRight() {
      isNotNull();
      if (actual.isLeft()) {
        failWithMessage(
          "Expected Either to be right but it is left: %s", actual.left());
      }
      return this;
    }
  }

  private static <A, B> EitherAssert<A, B> assertThatEither(Either<A, B> either) {
    return new EitherAssert<>(either);
  }

  private static final Logger LOG = Logger.getLogger("Distributor Test");
  private Tracer tracer;
  private EventBus bus;
  private Distributor local;
  private Capabilities stereotype;
  private Capabilities caps;
  private final Secret registrationSecret = new Secret("hellim");
  private final Wait<Object> wait = new FluentWait<>(new Object()).withTimeout(Duration.ofSeconds(5));

  @Before
  public void setUp() {
    tracer = DefaultTestTracer.createTracer();
    bus = new GuavaEventBus();
    LocalSessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);
    local = new LocalDistributor(
      tracer,
      bus,
      HttpClient.Factory.createDefault(),
      sessions,
      queuer,
      registrationSecret);
    stereotype = new ImmutableCapabilities("browserName", "cheese");
    caps = new ImmutableCapabilities("browserName", "cheese");
  }

  @Test
  public void creatingANewSessionWithoutANodeEndsInFailure() {
    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        local.newSession(createRequest(payload));
      assertThatEither(result).isLeft();
    }
  }

  @Test
  public void shouldBeAbleToAddANodeAndCreateASession() throws URISyntaxException {
    URI nodeUri = new URI("http://example:5678");
    URI routableUri = new URI("http://localhost:1234");

    LocalSessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);
    LocalNode node = LocalNode.builder(tracer, bus, routableUri, routableUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, c) -> new Session(id, nodeUri, stereotype, c, Instant.now())))
      .build();

    Distributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(node),
      sessions,
      queuer,
      registrationSecret);
    distributor.add(node);
    waitToHaveCapacity(distributor);

    MutableCapabilities sessionCaps = new MutableCapabilities(caps);
    sessionCaps.setCapability("sausages", "gravy");
    try (NewSessionPayload payload = NewSessionPayload.create(sessionCaps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isRight();
      Session session = result.right().getSession();
      assertThat(session.getCapabilities()).isEqualTo(sessionCaps);
      assertThat(session.getUri()).isEqualTo(routableUri);
    }
  }

  @Test
  public void creatingASessionAddsItToTheSessionMap() throws URISyntaxException {
    URI nodeUri = new URI("http://example:5678");
    URI routableUri = new URI("http://localhost:1234");

    LocalSessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);

    LocalNode node = LocalNode.builder(tracer, bus, routableUri, routableUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, c) -> new Session(id, nodeUri, stereotype, c, Instant.now())))
      .build();

    LocalDistributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(node),
      sessions,
      queuer,
      registrationSecret);
    distributor.add(node);
    waitToHaveCapacity(distributor);

    MutableCapabilities sessionCaps = new MutableCapabilities(caps);
    sessionCaps.setCapability("sausages", "gravy");
    try (NewSessionPayload payload = NewSessionPayload.create(sessionCaps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isRight();
      Session returned = result.right().getSession();
      Session session = sessions.get(returned.getId());
      assertThat(session.getCapabilities()).isEqualTo(sessionCaps);
      assertThat(session.getUri()).isEqualTo(routableUri);
    }
  }

  @Test
  public void shouldBeAbleToRemoveANode() throws URISyntaxException, MalformedURLException {
    URI nodeUri = new URI("http://example:5678");
    URI routableUri = new URI("http://localhost:1234");

    LocalSessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);

    LocalNode node = LocalNode.builder(tracer, bus, routableUri, routableUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, c) -> new Session(id, nodeUri, stereotype, c, Instant.now())))
      .build();

    Distributor local = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(node),
      sessions,
      queuer,
      registrationSecret);
    Distributor distributor = new RemoteDistributor(
      tracer,
      new PassthroughHttpClient.Factory(local),
      new URL("http://does.not.exist"),
      registrationSecret);
    distributor.add(node);
    distributor.remove(node.getId());

    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        local.newSession(createRequest(payload));
      assertThatEither(result).isLeft();
    }
  }

  @Test
  public void testDrainingNodeDoesNotAcceptNewSessions() throws URISyntaxException {
    URI nodeUri = new URI("http://example:5678");
    URI routableUri = new URI("http://localhost:1234");

    SessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);
    LocalNode node = LocalNode.builder(tracer, bus, routableUri, routableUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, c) -> new Session(id, nodeUri, stereotype, c, Instant.now())))
      .build();

    Distributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(node),
      sessions,
      queuer,
      registrationSecret);
    distributor.add(node);
    distributor.drain(node.getId());

    assertTrue(node.isDraining());

    NewSessionPayload payload = NewSessionPayload.create(caps);
    Either<SessionNotCreatedException, CreateSessionResponse> result =
      distributor.newSession(createRequest(payload));
    assertThatEither(result).isLeft();
  }

  @Test
  public void testDrainedNodeShutsDownOnceEmpty()
    throws URISyntaxException, InterruptedException
  {
    URI nodeUri = new URI("http://example:5678");
    URI routableUri = new URI("http://localhost:1234");

    SessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);
    LocalNode node = LocalNode.builder(tracer, bus, routableUri, routableUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, c) -> new Session(id, nodeUri, stereotype, c, Instant.now())))
      .build();

    CountDownLatch latch = new CountDownLatch(1);
    bus.addListener(NodeRemovedEvent.listener(ignored -> latch.countDown()));

    Distributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(node),
      sessions,
      queuer,
      registrationSecret);
    distributor.add(node);
    waitToHaveCapacity(distributor);

    distributor.drain(node.getId());

    latch.await(5, TimeUnit.SECONDS);

    assertThat(latch.getCount()).isEqualTo(0);

    assertThat(distributor.getAvailableNodes().size()).isEqualTo(0);

    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isLeft();
    }
  }

  @Test
  public void drainedNodeDoesNotShutDownIfNotEmpty()
    throws URISyntaxException, InterruptedException
  {
    URI nodeUri = new URI("http://example:5678");
    URI routableUri = new URI("http://localhost:1234");

    SessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);
    LocalNode node = LocalNode.builder(tracer, bus, routableUri, routableUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, c) -> new Session(id, nodeUri, stereotype, c, Instant.now())))
      .build();

    CountDownLatch latch = new CountDownLatch(1);
    bus.addListener(NodeRemovedEvent.listener(ignored -> latch.countDown()));

    Distributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(node),
      sessions,
      queuer,
      registrationSecret);
    distributor.add(node);
    waitToHaveCapacity(distributor);

    NewSessionPayload payload = NewSessionPayload.create(caps);
    Either<SessionNotCreatedException, CreateSessionResponse> session =
      distributor.newSession(createRequest(payload));
    assertThatEither(session).isRight();

    distributor.drain(node.getId());

    latch.await(5, TimeUnit.SECONDS);

    assertThat(latch.getCount()).isEqualTo(1);

    assertThat(distributor.getAvailableNodes().size()).isEqualTo(1);
  }

  @Test
  public void drainedNodeShutsDownAfterSessionsFinish()
    throws URISyntaxException, InterruptedException
  {
    URI nodeUri = new URI("http://example:5678");
    URI routableUri = new URI("http://localhost:1234");

    SessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);
    LocalNode node = LocalNode.builder(tracer, bus, routableUri, routableUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, c) -> new Session(id, nodeUri, stereotype, c, Instant.now())))
      .add(caps, new TestSessionFactory((id, c) -> new Session(id, nodeUri, stereotype, c, Instant.now())))
      .build();

    CountDownLatch latch = new CountDownLatch(1);
    bus.addListener(NodeRemovedEvent.listener(ignored -> latch.countDown()));

    Distributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(node),
      sessions,
      queuer,
      registrationSecret);
    distributor.add(node);
    waitToHaveCapacity(distributor);

    NewSessionPayload payload = NewSessionPayload.create(caps);

    Either<SessionNotCreatedException, CreateSessionResponse> firstResponse =
      distributor.newSession(createRequest(payload));

    Either<SessionNotCreatedException, CreateSessionResponse> secondResponse =
      distributor.newSession(createRequest(payload));

    distributor.drain(node.getId());

    assertThat(distributor.getAvailableNodes().size()).isEqualTo(1);

    node.stop(firstResponse.right().getSession().getId());
    node.stop(secondResponse.right().getSession().getId());

    latch.await(5, TimeUnit.SECONDS);

    assertThat(latch.getCount()).isEqualTo(0);
    assertThat(distributor.getAvailableNodes().size()).isEqualTo(0);
  }

  @Test
  public void registeringTheSameNodeMultipleTimesOnlyCountsTheFirstTime()
    throws URISyntaxException
  {
    URI nodeUri = new URI("http://example:5678");
    URI routableUri = new URI("http://localhost:1234");
    LocalNode node = LocalNode.builder(tracer, bus, routableUri, routableUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, c) -> new Session(id, nodeUri, stereotype, c, Instant.now())))
      .build();

    local.add(node);
    local.add(node);

    DistributorStatus status = local.getStatus();

    assertThat(status.getNodes().size()).isEqualTo(1);
  }

  @Test
  public void registeringTheWrongRegistrationSecretDoesNotWork()
    throws URISyntaxException, InterruptedException
  {
    URI nodeUri = new URI("http://example:5678");
    URI routableUri = new URI("http://localhost:1234");

    EventName rejected = new EventName("node-rejected");
    CountDownLatch latch = new CountDownLatch(1);
    bus.addListener(new EventListener<>(rejected, Object.class, obj -> latch.countDown()));

    LocalNode node = LocalNode.builder(tracer, bus, routableUri, routableUri, new Secret("pickles"))
      .add(caps, new TestSessionFactory((id, c) -> new Session(id, nodeUri, stereotype, c, Instant.now())))
      .build();

    local.add(node);

    latch.await(1, TimeUnit.SECONDS);

    assertThat(latch.getCount()).isEqualTo(1);
  }

  @Test
  public void registeringTheCorrectRegistrationSecretWorks()
    throws URISyntaxException, InterruptedException
  {
    URI nodeUri = new URI("http://example:5678");
    URI routableUri = new URI("http://localhost:1234");

    CountDownLatch latch = new CountDownLatch(1);
    bus.addListener(NodeAddedEvent.listener(ignored -> latch.countDown()));
    LocalNode node = LocalNode.builder(tracer, bus, routableUri, routableUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, c) -> new Session(id, nodeUri, stereotype, c, Instant.now())))
      .build();

    local.add(node);

    assertTrue(latch.await(1, TimeUnit.SECONDS));

    assertThat(latch.getCount()).isEqualTo(0);
  }

  @Test
  public void theMostLightlyLoadedNodeIsSelectedFirst() {
    // Create enough hosts so that we avoid the scheduler returning hosts in:
    // * insertion order
    // * reverse insertion order
    // * sorted with most heavily used first
    SessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);

    Node lightest = createNode(caps, 10, 0);
    Node medium = createNode(caps, 10, 4);
    Node heavy = createNode(caps, 10, 6);
    Node massive = createNode(caps, 10, 8);

    CombinedHandler handler = new CombinedHandler();
    handler.addHandler(lightest);
    handler.addHandler(medium);
    handler.addHandler(heavy);
    handler.addHandler(massive);
    Distributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(handler),
      sessions,
      queuer,
      registrationSecret)
      .add(heavy)
      .add(medium)
      .add(lightest)
      .add(massive);

    wait.until(obj -> distributor.getStatus().hasCapacity());

    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isRight();
      Session session = result.right().getSession();
      assertThat(session.getUri()).isEqualTo(lightest.getStatus().getUri());
    }
  }

  @Test
  public void shouldUseLastSessionCreatedTimeAsTieBreaker() {
    SessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);
    Node leastRecent = createNode(caps, 5, 0);

    CombinedHandler handler = new CombinedHandler();
    handler.addHandler(sessions);
    handler.addHandler(leastRecent);

    Distributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(handler),
      sessions,
      queuer,
      registrationSecret)
      .add(leastRecent);
    waitToHaveCapacity(distributor);

    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      distributor.newSession(createRequest(payload));
      // Will be "leastRecent" by default
    }

    Node middle = createNode(caps, 5, 0);
    handler.addHandler(middle);
    distributor.add(middle);
    waitForAllNodesToHaveCapacity(distributor, 2);

    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isRight();
      Session session = result.right().getSession();
      // Least lightly loaded is middle
      assertThat(session.getUri()).isEqualTo(middle.getStatus().getUri());
    }

    Node mostRecent = createNode(caps, 5, 0);
    handler.addHandler(mostRecent);
    distributor.add(mostRecent);
    waitForAllNodesToHaveCapacity(distributor, 3);

    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isRight();
      Session session = result.right().getSession();
      // Least lightly loaded is most recent
      assertThat(session.getUri()).isEqualTo(mostRecent.getStatus().getUri());
    }

    // All the nodes should be equally loaded.
    Map<Capabilities, Integer> expected = getFreeStereotypeCounts(mostRecent.getStatus());
    assertThat(getFreeStereotypeCounts(leastRecent.getStatus())).isEqualTo(expected);
    assertThat(getFreeStereotypeCounts(middle.getStatus())).isEqualTo(expected);

    // All nodes are now equally loaded. We should be going in time order now
    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isRight();
      Session session = result.right().getSession();
      assertThat(session.getUri()).isEqualTo(leastRecent.getStatus().getUri());
    }
  }

  private Map<Capabilities, Integer> getFreeStereotypeCounts(NodeStatus status) {
    Map<Capabilities, Integer> toReturn = new HashMap<>();
    for (Slot slot : status.getSlots()) {
      int count = toReturn.getOrDefault(slot.getStereotype(), 0);
      count++;
      toReturn.put(slot.getStereotype(), count);
    }
    return toReturn;
  }

  @Test
  public void shouldIncludeHostsThatAreUpInHostList() {
    CombinedHandler handler = new CombinedHandler();

    SessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);
    handler.addHandler(sessions);

    URI uri = createUri();
    Node alwaysDown = LocalNode.builder(tracer, bus, uri, uri, registrationSecret)
      .add(caps, new TestSessionFactory((id, c) -> new Session(id, uri, stereotype, c, Instant.now())))
      .advanced()
      .healthCheck(() -> new HealthCheck.Result(DOWN, "Boo!"))
      .build();
    handler.addHandler(alwaysDown);
    Node alwaysUp = LocalNode.builder(tracer, bus, uri, uri, registrationSecret)
      .add(caps, new TestSessionFactory((id, c) -> new Session(id, uri, stereotype, c, Instant.now())))
      .advanced()
      .healthCheck(() -> new HealthCheck.Result(UP, "Yay!"))
      .build();
    handler.addHandler(alwaysUp);

    LocalDistributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(handler),
      sessions,
      queuer,
      registrationSecret);
    handler.addHandler(distributor);
    distributor.add(alwaysDown);

    // Should be unable to create a session because the node is down.
    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isLeft();
    }

    distributor.add(alwaysUp);
    waitToHaveCapacity(distributor);

    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isRight();
    }
  }

  @Test
  public void shouldNotScheduleAJobIfAllSlotsAreBeingUsed() throws URISyntaxException {
    URI nodeUri = new URI("http://example:5678");
    URI routableUri = new URI("http://localhost:1234");
    SessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);

    LocalNode node = LocalNode.builder(tracer, bus, routableUri, routableUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, c) -> new Session(
        id, nodeUri, stereotype, c, Instant.now())))
      .build();
    Distributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(node),
      sessions,
      queuer,
      registrationSecret);

    distributor.add(node);
    waitToHaveCapacity(distributor);

    // Use up the one slot available
    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isRight();
    }

    // Now try and create a session.
    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isLeft();
    }
  }

  @Test
  public void shouldReleaseSlotOnceSessionEnds() throws URISyntaxException {
    URI nodeUri = new URI("http://example:5678");
    URI routableUri = new URI("http://localhost:1234");
    SessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);

    LocalNode node = LocalNode.builder(tracer, bus, routableUri, routableUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, c) -> new Session(
        id, nodeUri, stereotype, c, Instant.now())))
      .build();

    Distributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(node),
      sessions,
      queuer,
      registrationSecret);
    distributor.add(node);
    waitToHaveCapacity(distributor);

    // Use up the one slot available
    Session session;
    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isRight();
      session = result.right().getSession();
      // Make sure the session map has the session
      sessions.get(session.getId());

      node.stop(session.getId());
      // Now wait for the session map to say the session is gone.
      wait.until(obj -> {
        try {
          sessions.get(session.getId());
          return false;
        } catch (NoSuchSessionException e) {
          return true;
        }
      });
    }

    waitToHaveCapacity(distributor);

    // And we should now be able to create another session.
    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isRight();
    }
  }

  @Test
  public void shouldNotStartASessionIfTheCapabilitiesAreNotSupported() {
    CombinedHandler handler = new CombinedHandler();

    LocalSessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);
    handler.addHandler(handler);

    Distributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(handler),
      sessions,
      queuer,
      registrationSecret);
    handler.addHandler(distributor);

    Node node = createNode(caps, 1, 0);
    handler.addHandler(node);
    distributor.add(node);
    waitToHaveCapacity(distributor);

    ImmutableCapabilities unmatched = new ImmutableCapabilities("browserName", "transit of venus");
    try (NewSessionPayload payload = NewSessionPayload.create(unmatched)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isLeft();
    }
  }

  @Test
  public void attemptingToStartASessionWhichFailsMarksAsTheSlotAsAvailable() throws URISyntaxException {
    URI nodeUri = new URI("http://example:5678");
    URI routableUri = new URI("http://localhost:1234");

    SessionMap sessions = new LocalSessionMap(tracer, bus);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);

    LocalNode node = LocalNode.builder(tracer, bus, routableUri, routableUri, registrationSecret)
      .add(caps, new TestSessionFactory((id, caps) -> {
        throw new SessionNotCreatedException("OMG");
      }))
      .build();

    Distributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(node),
      sessions,
      queuer,
      registrationSecret);
    distributor.add(node);
    waitToHaveCapacity(distributor);

    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isLeft();
    }

    assertThat(distributor.getStatus().hasCapacity()).isTrue();
  }

  @Test
  public void shouldReturnNodesThatWereDownToPoolOfNodesOnceTheyMarkTheirHealthCheckPasses() {
    CombinedHandler handler = new CombinedHandler();

    SessionMap sessions = new LocalSessionMap(tracer, bus);
    handler.addHandler(sessions);
    AtomicReference<Availability> isUp = new AtomicReference<>(DOWN);
    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);

    URI uri = createUri();
    Node node = LocalNode.builder(tracer, bus, uri, uri, registrationSecret)
      .add(caps, new TestSessionFactory((id, caps) -> new Session(id, uri, stereotype, caps, Instant.now())))
      .advanced()
      .healthCheck(() -> new HealthCheck.Result(isUp.get(), "TL;DR"))
      .build();
    handler.addHandler(node);

    LocalDistributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(handler),
      sessions,
      queuer,
      registrationSecret);
    handler.addHandler(distributor);
    distributor.add(node);

    // Should be unable to create a session because the node is down.
    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isLeft();
    }

    // Mark the node as being up
    isUp.set(UP);
    // Kick the machinery to ensure that everything is fine.
    distributor.refresh();

    // Because the node is now up and running, we should now be able to create a session
    try (NewSessionPayload payload = NewSessionPayload.create(caps)) {
      Either<SessionNotCreatedException, CreateSessionResponse> result =
        distributor.newSession(createRequest(payload));
      assertThatEither(result).isRight();
    }
  }

  private Set<Node> createNodeSet(Distributor distributor, int count, Capabilities...capabilities) {
    Set<Node> nodeSet = new HashSet<>();
    for (int i=0; i<count; i++) {
      URI uri = createUri();
      LocalNode.Builder builder = LocalNode.builder(tracer, bus, uri, uri, registrationSecret);
      for (Capabilities caps: capabilities) {
        builder.add(caps, new TestSessionFactory((id, hostCaps) -> new HandledSession(uri, hostCaps)));
      }
      Node node = builder.build();
      distributor.add(node);
      nodeSet.add(node);
    }
    return nodeSet;
  }

  @Test
  public void shouldPrioritizeHostsWithTheMostSlotsAvailableForASessionType() {
    //SS: Consider the case where you have 1 Windows machine and 5 linux machines. All of these hosts
    // can run Chrome and Firefox sessions, but only one can run Edge sessions. Ideally, the machine
    // able to run Edge would be sorted last.

    //Create the Distributor
    CombinedHandler handler = new CombinedHandler();
    SessionMap sessions = new LocalSessionMap(tracer, bus);
    handler.addHandler(sessions);

    LocalNewSessionQueue localNewSessionQueue = new LocalNewSessionQueue(
      tracer,
      bus,
      Duration.ofSeconds(2),
      Duration.ofSeconds(2));
    LocalNewSessionQueuer queuer = new LocalNewSessionQueuer(tracer, bus, localNewSessionQueue);

    LocalDistributor distributor = new LocalDistributor(
      tracer,
      bus,
      new PassthroughHttpClient.Factory(handler),
      sessions,
      queuer,
      registrationSecret);
    handler.addHandler(distributor);

    //Create all three Capability types
    Capabilities edgeCapabilities = new ImmutableCapabilities("browserName", "edge");
    Capabilities firefoxCapabilities = new ImmutableCapabilities("browserName", "firefox");
    Capabilities chromeCapabilities = new ImmutableCapabilities("browserName", "chrome");

    //TODO This should probably be a map of browser -> all nodes that support <browser>
    //Store our "expected results" sets for the various browser-specific nodes
    Set<Node> edgeNodes = createNodeSet(distributor, 3, edgeCapabilities, chromeCapabilities, firefoxCapabilities);

    //chromeNodes is all these new nodes PLUS all the Edge nodes from before
    Set<Node> chromeNodes = createNodeSet(distributor,5, chromeCapabilities, firefoxCapabilities);
    chromeNodes.addAll(edgeNodes);

    //all nodes support firefox, so add them to the firefoxNodes set
    Set<Node> firefoxNodes = createNodeSet(distributor,3, firefoxCapabilities);
    firefoxNodes.addAll(edgeNodes);
    firefoxNodes.addAll(chromeNodes);

    waitForAllNodesToHaveCapacity(distributor, 11);

    //Assign 5 Chrome and 5 Firefox sessions to the distributor, make sure they don't go to the Edge node
    for (int i=0; i<5; i++) {
      try (NewSessionPayload chromePayload = NewSessionPayload.create(chromeCapabilities);
           NewSessionPayload firefoxPayload = NewSessionPayload.create(firefoxCapabilities)) {

        Either<SessionNotCreatedException, CreateSessionResponse> chromeResult =
          distributor.newSession(createRequest(chromePayload));
        assertThatEither(chromeResult).isRight();
        Session chromeSession = chromeResult.right().getSession();

        //Ensure the Uri of the Session matches one of the Chrome Nodes, not the Edge Node
        assertThat(
          chromeSession.getUri()).isIn(
          chromeNodes
            .stream().map(Node::getStatus).collect(Collectors.toList())     //List of getStatus() from the Set
            .stream().map(NodeStatus::getUri).collect(Collectors.toList())  //List of getUri() from the Set
        );

        Either<SessionNotCreatedException, CreateSessionResponse> firefoxResult =
          distributor.newSession(createRequest(firefoxPayload));
        assertThatEither(firefoxResult).isRight();
        Session firefoxSession = firefoxResult.right().getSession();
        LOG.info(String.format("Firefox Session %d assigned to %s", i, chromeSession.getUri()));

        boolean inFirefoxNodes = firefoxNodes.stream().anyMatch(node -> node.getUri().equals(firefoxSession.getUri()));
        boolean inChromeNodes = chromeNodes.stream().anyMatch(node -> node.getUri().equals(chromeSession.getUri()));
        //This could be either, or, or both
        assertTrue(inFirefoxNodes || inChromeNodes);
      }
    }

    //The Chrome Nodes should be full at this point, but Firefox isn't... so send an Edge session and make sure it routes to an Edge node
    try (NewSessionPayload edgePayload = NewSessionPayload.create(edgeCapabilities)) {
      Either<SessionNotCreatedException, CreateSessionResponse> edgeResult =
        distributor.newSession(createRequest(edgePayload));
      assertThatEither(edgeResult).isRight();
      Session edgeSession = edgeResult.right().getSession();
      assertTrue(edgeNodes.stream().anyMatch(node -> node.getUri().equals(edgeSession.getUri())));
    }
  }

  private Node createNode(Capabilities stereotype, int count, int currentLoad) {
    URI uri = createUri();
    LocalNode.Builder builder = LocalNode.builder(tracer, bus, uri, uri, registrationSecret);
    for (int i = 0; i < count; i++) {
      builder.add(stereotype, new TestSessionFactory((id, caps) -> new HandledSession(uri, caps)));
    }

    LocalNode node = builder.build();
    for (int i = 0; i < currentLoad; i++) {
      // Ignore the session. We're just creating load.
      node.newSession(new CreateSessionRequest(
        ImmutableSet.copyOf(Dialect.values()),
        stereotype,
        ImmutableMap.of()));
    }

    return node;
  }

  @Test
  @Ignore
  public void shouldCorrectlySetSessionCountsWhenStartedAfterNodeWithSession() {
    fail("write me!");
  }

  @Test
  public void statusShouldIndicateThatDistributorIsNotAvailableIfNodesAreDown()
    throws URISyntaxException
  {
    Capabilities capabilities = new ImmutableCapabilities("cheese", "peas");
    URI uri = new URI("http://example.com");

    Node node = LocalNode.builder(tracer, bus, uri, uri, registrationSecret)
      .add(capabilities, new TestSessionFactory((id, caps) -> new Session(id, uri, stereotype, caps, Instant.now())))
      .advanced()
      .healthCheck(() -> new HealthCheck.Result(DOWN, "TL;DR"))
      .build();

    local.add(node);

    DistributorStatus status = local.getStatus();
    assertFalse(status.hasCapacity());
  }

  @Test
  public void disabledNodeShouldNotAcceptNewRequests()
    throws URISyntaxException
  {
    Capabilities capabilities = new ImmutableCapabilities("cheese", "peas");

    URI uri = new URI("http://example.com");
    Node node = LocalNode.builder(tracer, bus, uri, uri, registrationSecret)
      .add(capabilities, new TestSessionFactory((id, caps) -> new Session(id, uri, stereotype, caps, Instant.now())))
      .advanced()
      .healthCheck(() -> new HealthCheck.Result(DOWN, "TL;DR"))
      .build();

    local.add(node);

    DistributorStatus status = local.getStatus();
    assertFalse(status.hasCapacity());
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

  private URI createUri() {
    try {
      return new URI("http://localhost:" + PortProber.findFreePort());
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  class HandledSession extends Session implements HttpHandler {

    HandledSession(URI uri, Capabilities caps) {
      super(new SessionId(UUID.randomUUID()), uri, stereotype, caps, Instant.now());
    }

    @Override
    public HttpResponse execute(HttpRequest req) throws UncheckedIOException {
      // no-op
      return new HttpResponse();
    }
  }

  private void waitToHaveCapacity(Distributor distributor) {
    new FluentWait<>(distributor)
      .withTimeout(Duration.ofSeconds(5))
      .pollingEvery(Duration.ofMillis(100))
      .until(d -> d.getStatus().hasCapacity());
  }

  private void waitForAllNodesToHaveCapacity(Distributor distributor, int nodeCount) {
    try {
      new FluentWait<>(distributor)
        .withTimeout(Duration.ofSeconds(5))
        .pollingEvery(Duration.ofMillis(100))
        .until(d -> {
          Set<NodeStatus> nodes = d.getStatus().getNodes();
          return nodes.size() == nodeCount && nodes.stream().allMatch(
            node -> node.getAvailability() == UP && node.hasCapacity());
        });
    } catch (TimeoutException ex) {
      Set<NodeStatus> nodes = distributor.getStatus().getNodes();
      System.out.println("*************");
      System.out.println("" + nodes.size());
      nodes.forEach(node -> System.out.println("" + node.hasCapacity()));
    }
  }
}
