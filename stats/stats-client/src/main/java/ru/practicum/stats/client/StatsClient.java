package ru.practicum.stats.client;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.MaxAttemptsRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class StatsClient {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestTemplate restTemplate;
    private final DiscoveryClient discoveryClient;
    private final String statsServiceId;
    private final String serverUrl;
    private final RetryTemplate retryTemplate;

    // Конструкторы для работы с Eureka через DiscoveryClient
    public StatsClient(DiscoveryClient discoveryClient, String statsServiceId) {
        this(discoveryClient, statsServiceId, new RestTemplate());
    }

    public StatsClient(DiscoveryClient discoveryClient, String statsServiceId, RestTemplate restTemplate) {
        this.discoveryClient = discoveryClient;
        this.statsServiceId = statsServiceId;
        this.serverUrl = null;
        this.restTemplate = restTemplate;
        this.retryTemplate = initRetryTemplate();
    }

    // Конструкторы для тестов (прямой URL)
    public StatsClient(String serverUrl) {
        this(serverUrl, new RestTemplate());
    }

    public StatsClient(String serverUrl, RestTemplate restTemplate) {
        this.discoveryClient = null;
        this.statsServiceId = null;
        this.serverUrl = serverUrl;
        this.restTemplate = restTemplate;
        this.retryTemplate = null;
    }

    private RetryTemplate initRetryTemplate() {
        RetryTemplate template = new RetryTemplate();

        FixedBackOffPolicy fixedBackOffPolicy = new FixedBackOffPolicy();
        fixedBackOffPolicy.setBackOffPeriod(3000L);
        template.setBackOffPolicy(fixedBackOffPolicy);

        MaxAttemptsRetryPolicy retryPolicy = new MaxAttemptsRetryPolicy();
        retryPolicy.setMaxAttempts(3);
        template.setRetryPolicy(retryPolicy);

        return template;
    }

    private ServiceInstance getInstance() {
        try {
            return discoveryClient
                    .getInstances(statsServiceId)
                    .getFirst();
        } catch (Exception exception) {
            throw new StatsServerUnavailable(
                    "Ошибка обнаружения адреса сервиса статистики с id: " + statsServiceId,
                    exception
            );
        }
    }

    private URI makeUri(String path) {
        if (serverUrl != null) {
            return URI.create(serverUrl + path);
        }
        ServiceInstance instance = retryTemplate.execute(cxt -> getInstance());
        return URI.create("http://" + instance.getHost() + ":" + instance.getPort() + path);
    }

    public void saveHit(String app, String uri, String ip) {
        EndpointHit hit = EndpointHit.builder()
                .app(app)
                .uri(uri)
                .ip(ip)
                .timestamp(LocalDateTime.now().format(FORMATTER))
                .build();

        URI targetUri = makeUri("/hit");
        restTemplate.postForEntity(targetUri, hit, Void.class);
    }

    @SuppressWarnings("unchecked")
    public List<ViewStats> getStats(LocalDateTime start, LocalDateTime end, List<String> uris, Boolean unique) {
        String startStr = start.format(FORMATTER);
        String endStr = end.format(FORMATTER);

        String baseUrl;
        if (serverUrl != null) {
            baseUrl = serverUrl + "/stats";
        } else {
            ServiceInstance instance = retryTemplate.execute(cxt -> getInstance());
            baseUrl = "http://" + instance.getHost() + ":" + instance.getPort() + "/stats";
        }

        UriComponentsBuilder urlBuild = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("start", startStr)
                .queryParam("end", endStr);

        if (uris != null && !uris.isEmpty()) {
            for (String uri : uris) {
                urlBuild.queryParam("uris", uri);
            }
        }

        if (unique != null && unique) {
            urlBuild.queryParam("unique", true);
        }

        URI targetUri = urlBuild.build().toUri();
        return restTemplate.getForObject(targetUri, List.class);
    }
}