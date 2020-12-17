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

package org.openqa.selenium.grid.docker;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.Platform;
import org.openqa.selenium.docker.ContainerId;
import org.openqa.selenium.docker.ContainerInfo;
import org.openqa.selenium.docker.Docker;
import org.openqa.selenium.docker.DockerException;
import org.openqa.selenium.docker.Image;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.config.ConfigException;
import org.openqa.selenium.grid.node.SessionFactory;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.tracing.Tracer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import static org.openqa.selenium.Platform.WINDOWS;

public class DockerOptions {

  private static final String DOCKER_SECTION = "docker";
  private static final String CONTAINER_ASSETS_PATH = "/opt/selenium/assets";
  private static final String DEFAULT_VIDEO_IMAGE = "selenium/video:latest";

  private static final Logger LOG = Logger.getLogger(DockerOptions.class.getName());
  private static final Json JSON = new Json();
  private final Config config;

  public DockerOptions(Config config) {
    this.config = Require.nonNull("Config", config);
  }

  private URI getDockerUri() {
    try {
      Optional<String> possibleUri = config.get(DOCKER_SECTION, "url");
      if (possibleUri.isPresent()) {
        return new URI(possibleUri.get());
      }

      Optional<String> possibleHost = config.get(DOCKER_SECTION, "host");
      if (possibleHost.isPresent()) {
        String host = possibleHost.get();
        if (!(host.startsWith("tcp:") || host.startsWith("http:") || host.startsWith("https"))) {
          host = "http://" + host;
        }
        URI uri = new URI(host);
        return new URI(
          "http",
          uri.getUserInfo(),
          uri.getHost(),
          uri.getPort(),
          uri.getPath(),
          null,
          null);
      }

      // Default for the system we're running on.
      if (Platform.getCurrent().is(WINDOWS)) {
        return new URI("http://localhost:2376");
      }
      return new URI("unix:/var/run/docker.sock");
    } catch (URISyntaxException e) {
      throw new ConfigException("Unable to determine docker url", e);
    }
  }

  private boolean isEnabled(Docker docker) {
    if (!config.getAll(DOCKER_SECTION, "configs").isPresent()) {
      return false;
    }

    // Is the daemon up and running?
    return docker.isSupported();
  }

  public Map<Capabilities, Collection<SessionFactory>> getDockerSessionFactories(
    Tracer tracer,
    HttpClient.Factory clientFactory) {

    HttpClient client = clientFactory.createClient(ClientConfig.defaultConfig().baseUri(getDockerUri()));
    Docker docker = new Docker(client);

    if (!isEnabled(docker)) {
      LOG.warning("Unable to reach the Docker daemon.");
      return ImmutableMap.of();
    }

    DockerAssetsPath assetsPath = getAssetsPath(docker);

    List<String> allConfigs = config.getAll(DOCKER_SECTION, "configs")
        .orElseThrow(() -> new DockerException("Unable to find docker configs"));

    Multimap<String, Capabilities> kinds = HashMultimap.create();
    for (int i = 0; i < allConfigs.size(); i++) {
      String imageName = allConfigs.get(i);
      i++;
      if (i == allConfigs.size()) {
        throw new DockerException("Unable to find JSON config");
      }
      Capabilities stereotype = JSON.toType(allConfigs.get(i), Capabilities.class);

      kinds.put(imageName, stereotype);
    }

    loadImages(docker, kinds.keySet().toArray(new String[0]));
    Image videoImage = getVideoImage(docker);
    loadImages(docker, videoImage.getName());

    int maxContainerCount = Runtime.getRuntime().availableProcessors();
    ImmutableMultimap.Builder<Capabilities, SessionFactory> factories = ImmutableMultimap.builder();
    kinds.forEach((name, caps) -> {
      Image image = docker.getImage(name);
      for (int i = 0; i < maxContainerCount; i++) {
        factories.put(
          caps,
          new DockerSessionFactory(
            tracer,
            clientFactory,
            docker,
            getDockerUri(),
            image,
            caps,
            videoImage,
            assetsPath));
      }
      LOG.info(String.format(
          "Mapping %s to docker image %s %d times",
          caps,
          name,
          maxContainerCount));
    });
    return factories.build().asMap();
  }

  private Image getVideoImage(Docker docker) {
    String videoImage = config.get(DOCKER_SECTION, "video-image").orElse(DEFAULT_VIDEO_IMAGE);
    return docker.getImage(videoImage);
  }

  private DockerAssetsPath getAssetsPath(Docker docker) {
    Optional<String> assetsPath = config.get(DOCKER_SECTION, "assets-path");
    if (assetsPath.isPresent()) {
      // We assume the user is not running the Selenium Server inside a Docker container
      // Therefore, we have access to the assets path on the host
      return new DockerAssetsPath(assetsPath.get(), assetsPath.get());
    }
    // Selenium Server is running inside a Docker container, we will inspect that container
    // to get the mounted volume and use that. If no volume was mounted, no assets will be saved.
    // Since Docker 1.12, the env var HOSTNAME has the container id (unless the user overwrites it)
    String hostname = System.getenv("HOSTNAME");
    ContainerInfo info = docker.inspect(new ContainerId(hostname));
    Optional<Map<String, Object>> mountedVolume = info.getMountedVolumes()
      .stream()
      .filter(
        mounted ->
          CONTAINER_ASSETS_PATH.equalsIgnoreCase(String.valueOf(mounted.get("Destination"))))
      .findFirst();
    if (mountedVolume.isPresent()) {
      String hostPath = String.valueOf(mountedVolume.get().get("Source"));
      return new DockerAssetsPath(hostPath, CONTAINER_ASSETS_PATH);
    }
    return null;
  }

  private void loadImages(Docker docker, String... imageNames) {
    CompletableFuture<Void> cd = CompletableFuture.allOf(
        Arrays.stream(imageNames)
            .map(name -> CompletableFuture.supplyAsync(() -> docker.getImage(name)))
          .toArray(CompletableFuture[]::new));

    try {
      cd.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw new RuntimeException(cause);
    }
  }
}
