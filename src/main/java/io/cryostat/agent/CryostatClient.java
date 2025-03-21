/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.agent;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

import io.cryostat.agent.WebServer.Credentials;
import io.cryostat.agent.model.DiscoveryNode;
import io.cryostat.agent.model.PluginInfo;
import io.cryostat.agent.model.RegistrationInfo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.entity.mime.content.StringBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CryostatClient {

    private static final String DISCOVERY_API_PATH = "/api/v2.2/discovery";
    private static final String CREDENTIALS_API_PATH = "/api/v2.2/credentials";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Executor executor;
    private final ObjectMapper mapper;
    private final HttpClient http;

    private final String appName;
    private final String instanceId;
    private final String jvmId;
    private final URI baseUri;
    private final String realm;

    CryostatClient(
            Executor executor,
            ObjectMapper mapper,
            HttpClient http,
            String instanceId,
            String jvmId,
            String appName,
            URI baseUri,
            String realm) {
        this.executor = executor;
        this.mapper = mapper;
        this.http = http;
        this.instanceId = instanceId;
        this.jvmId = jvmId;
        this.appName = appName;
        this.baseUri = baseUri;
        this.realm = realm;

        log.info("Using Cryostat baseuri {}", baseUri);
    }

    public CompletableFuture<Boolean> checkRegistration(PluginInfo pluginInfo) {
        if (!pluginInfo.isInitialized()) {
            return CompletableFuture.completedFuture(false);
        }
        HttpGet req =
                new HttpGet(
                        baseUri.resolve(
                                DISCOVERY_API_PATH
                                        + "/"
                                        + pluginInfo.getId()
                                        + "?token="
                                        + pluginInfo.getToken()));
        log.info("{}", req);
        return supply(req, (res) -> logResponse(req, res)).thenApply(this::isOkStatus);
    }

    public CompletableFuture<PluginInfo> register(PluginInfo pluginInfo, URI callback) {
        try {
            RegistrationInfo registrationInfo =
                    new RegistrationInfo(
                            pluginInfo.getId(), realm, callback, pluginInfo.getToken());
            HttpPost req = new HttpPost(baseUri.resolve(DISCOVERY_API_PATH));
            log.info("{}", req);
            req.setEntity(
                    new StringEntity(
                            mapper.writeValueAsString(registrationInfo),
                            ContentType.APPLICATION_JSON));
            return supply(req, (res) -> logResponse(req, res))
                    .thenApply(res -> assertOkStatus(req, res))
                    .thenApply(
                            res -> {
                                try (InputStream is = res.getEntity().getContent()) {
                                    return mapper.readValue(is, ObjectNode.class);
                                } catch (IOException e) {
                                    log.error("Unable to parse response as JSON", e);
                                    throw new RegistrationException(e);
                                }
                            })
                    .thenApply(
                            node -> {
                                try {
                                    return mapper.readValue(
                                            node.get("data").get("result").toString(),
                                            PluginInfo.class);
                                } catch (IOException e) {
                                    log.error("Unable to parse response as JSON", e);
                                    throw new RegistrationException(e);
                                }
                            });
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Integer> submitCredentialsIfRequired(
            int prevId, Credentials credentials, URI callback) {
        if (prevId < 0) {
            return submitCredentials(credentials, callback);
        }
        HttpGet req = new HttpGet(baseUri.resolve(CREDENTIALS_API_PATH + "/" + prevId));
        log.info("{}", req);
        return supply(req, (res) -> logResponse(req, res))
                .thenApply(this::isOkStatus)
                .thenCompose(
                        exists -> {
                            if (exists) {
                                return CompletableFuture.completedFuture(prevId);
                            }
                            return submitCredentials(credentials, callback);
                        });
    }

    private CompletableFuture<Integer> submitCredentials(Credentials credentials, URI callback) {
        HttpPost req = new HttpPost(baseUri.resolve(CREDENTIALS_API_PATH));
        MultipartEntityBuilder entityBuilder =
                MultipartEntityBuilder.create()
                        .addPart(
                                FormBodyPartBuilder.create(
                                                "username",
                                                new StringBody(
                                                        credentials.user(), ContentType.TEXT_PLAIN))
                                        .build())
                        .addPart(
                                FormBodyPartBuilder.create(
                                                "password",
                                                new InputStreamBody(
                                                        new ByteArrayInputStream(
                                                                credentials.pass()),
                                                        ContentType.TEXT_PLAIN))
                                        .build())
                        .addPart(
                                FormBodyPartBuilder.create(
                                                "matchExpression",
                                                new StringBody(
                                                        selfMatchExpression(callback),
                                                        ContentType.TEXT_PLAIN))
                                        .build());
        log.info("{}", req);
        req.setEntity(entityBuilder.build());
        return supply(req, (res) -> logResponse(req, res))
                .thenApply(res -> assertOkStatus(req, res))
                .thenApply(res -> res.getFirstHeader(HttpHeaders.LOCATION).getValue())
                .thenApply(res -> res.substring(res.lastIndexOf('/') + 1, res.length()))
                .thenApply(Integer::valueOf);
    }

    public CompletableFuture<Void> deleteCredentials(int id) {
        if (id < 0) {
            return CompletableFuture.completedFuture(null);
        }
        HttpDelete req = new HttpDelete(baseUri.resolve(CREDENTIALS_API_PATH + "/" + id));
        log.info("{}", req);
        return supply(req, (res) -> logResponse(req, res))
                .thenApply(res -> assertOkStatus(req, res))
                .thenApply(res -> null);
    }

    public CompletableFuture<Void> deregister(PluginInfo pluginInfo) {
        HttpDelete req =
                new HttpDelete(
                        baseUri.resolve(
                                DISCOVERY_API_PATH
                                        + "/"
                                        + pluginInfo.getId()
                                        + "?token="
                                        + pluginInfo.getToken()));
        log.info("{}", req);
        return supply(req, (res) -> logResponse(req, res))
                .thenApply(res -> assertOkStatus(req, res))
                .thenApply(res -> null);
    }

    public CompletableFuture<Void> update(PluginInfo pluginInfo, Set<DiscoveryNode> subtree) {
        try {
            HttpPost req =
                    new HttpPost(
                            baseUri.resolve(
                                    DISCOVERY_API_PATH
                                            + "/"
                                            + pluginInfo.getId()
                                            + "?token="
                                            + pluginInfo.getToken()));
            req.setEntity(
                    new StringEntity(
                            mapper.writeValueAsString(subtree), ContentType.APPLICATION_JSON));

            log.info("{}", req);
            return supply(req, (res) -> logResponse(req, res))
                    .thenApply(res -> assertOkStatus(req, res))
                    .thenApply(res -> null);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Void> upload(
            Harvester.PushType pushType, String template, int maxFiles, Path recording)
            throws IOException {
        Instant start = Instant.now();
        String timestamp = start.truncatedTo(ChronoUnit.SECONDS).toString().replaceAll("[-:]", "");
        String fileName = String.format("%s_%s_%s.jfr", appName, template, timestamp);
        Map<String, String> labels =
                Map.of(
                        "jvmId",
                        jvmId,
                        "template.name",
                        template,
                        "template.type",
                        "TARGET",
                        "pushType",
                        pushType.name());

        HttpPost req = new HttpPost(baseUri.resolve("/api/beta/recordings/" + jvmId));

        CountingInputStream is = getRecordingInputStream(recording);
        MultipartEntityBuilder entityBuilder =
                MultipartEntityBuilder.create()
                        .addPart(
                                FormBodyPartBuilder.create(
                                                "recording",
                                                new InputStreamBody(
                                                        is,
                                                        ContentType.APPLICATION_OCTET_STREAM,
                                                        fileName))
                                        .build())
                        .addPart(
                                FormBodyPartBuilder.create(
                                                "labels",
                                                new StringBody(
                                                        mapper.writeValueAsString(labels),
                                                        ContentType.APPLICATION_JSON))
                                        .build())
                        .addPart(
                                FormBodyPartBuilder.create(
                                                "maxFiles",
                                                new StringBody(
                                                        Integer.toString(maxFiles),
                                                        ContentType.TEXT_PLAIN))
                                        .build());
        req.setEntity(entityBuilder.build());
        return supply(
                req,
                (res) -> {
                    Instant finish = Instant.now();
                    log.info(
                            "{} {} ({} -> {}): {}/{}",
                            req.getMethod(),
                            res.getStatusLine().getStatusCode(),
                            fileName,
                            req.getURI(),
                            FileUtils.byteCountToDisplaySize(is.getByteCount()),
                            Duration.between(start, finish));
                    assertOkStatus(req, res);
                    return (Void) null;
                });
    }

    private HttpResponse logResponse(HttpRequestBase req, HttpResponse res) {
        log.info("{} {} : {}", req.getMethod(), req.getURI(), res.getStatusLine().getStatusCode());
        return res;
    }

    private <T> CompletableFuture<T> supply(HttpRequestBase req, Function<HttpResponse, T> fn) {
        return CompletableFuture.supplyAsync(() -> fn.apply(executeQuiet(req)), executor)
                .whenComplete((v, t) -> req.reset());
    }

    private HttpResponse executeQuiet(HttpUriRequest req) {
        try {
            return http.execute(req);
        } catch (IOException ioe) {
            throw new CompletionException(ioe);
        }
    }

    private CountingInputStream getRecordingInputStream(Path filePath) throws IOException {
        return new CountingInputStream(new BufferedInputStream(Files.newInputStream(filePath)));
    }

    private String selfMatchExpression(URI callback) {
        return String.format(
                "target.connectUrl == \"%s\" && target.jvmId == \"%s\" &&"
                        + " target.annotations.platform[\"INSTANCE_ID\"] == \"%s\"",
                callback, jvmId, instanceId);
    }

    private boolean isOkStatus(HttpResponse res) {
        int sc = res.getStatusLine().getStatusCode();
        return 200 <= sc && sc < 300;
    }

    private HttpResponse assertOkStatus(HttpRequestBase req, HttpResponse res) {
        int sc = res.getStatusLine().getStatusCode();
        if (!isOkStatus(res)) {
            URI uri = req.getURI();
            log.error("Non-OK response ({}) on HTTP API {}", sc, uri);
            try {
                throw new HttpException(
                        sc,
                        new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null));
            } catch (URISyntaxException use) {
                throw new IllegalStateException(use);
            }
        }
        return res;
    }
}
