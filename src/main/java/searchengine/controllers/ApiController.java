package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import searchengine.config.SitesList;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.model.SiteEntity;
import searchengine.model.SiteStatus;
import searchengine.repositories.SiteRepository;
import searchengine.responses.NotOkResponse;
import searchengine.responses.OkResponse;
import searchengine.responses.SearchResponse;
import searchengine.services.ApiService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;


@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {
    private final SiteRepository siteRepository;

    private final SearchService searchService;
    private final StatisticsService statisticsService;
    private final ApiService apiService;
    private final AtomicBoolean indexingProcessing = new AtomicBoolean(false);
    private final SitesList sitesList;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() throws MalformedURLException {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity startIndexing() {
        if (indexingProcessing.get()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new NotOkResponse("Индексация уже запущена"));
        } else {
            executor.submit(() -> {
                indexingProcessing.set(true);
                apiService.startIndexing(indexingProcessing);
            });
            return ResponseEntity.status(HttpStatus.OK).body(new OkResponse());
        }
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity stopIndexing() {
        if (!indexingProcessing.get()) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(new NotOkResponse("Индексация не запущена"));
        } else {
            indexingProcessing.set(false);
            return ResponseEntity.status(HttpStatus.OK).body(new OkResponse());
        }
    }

    @PostMapping("/indexPage")
    public ResponseEntity indexPage(@RequestParam(name = "url") String url) throws IOException {
        URL refUrl = new URL(url);
        SiteEntity siteEntity = new SiteEntity();
        try {
            sitesList.getSites().stream()
                    .filter(site -> refUrl.getHost().equals(site.getUrl().getHost()))
                    .findFirst()
                    .map(site -> {
                        siteEntity.setName(site.getName());
                        siteEntity.setUrl(site.getUrl().toString());
                        return siteEntity;
                    }).orElseThrow();
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new NotOkResponse("Данная страница находится за пределами сайтов указанных в конфигурационном файле"));
        }

        apiService.refreshEntity(siteEntity, refUrl);
        return ResponseEntity.status(HttpStatus.OK).body(new OkResponse());
    }

    @GetMapping("/search")
    public ResponseEntity<Object> search(
            @RequestParam(name="query", required=false, defaultValue="") String query,
            @RequestParam(name="site", required=false, defaultValue="") String site,
            @RequestParam(name="offset", required=false, defaultValue="0") Integer offset,
            @RequestParam(name="limit", required=false, defaultValue="20") Integer limit
    ) throws IOException {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(new NotOkResponse("Задан пустой поисковый запрос"));
        }
        return searchService.search(query, site, offset, limit);
    }
}